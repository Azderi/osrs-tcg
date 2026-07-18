package com.osrstcg.service;

import com.osrstcg.model.OwnedCardInstance;
import com.osrstcg.model.RewardTuningState;
import com.osrstcg.party.TcgTradeCancelPartyMessage;
import com.osrstcg.party.TcgTradeCommitPartyMessage;
import com.osrstcg.party.TcgTradeInviteAckPartyMessage;
import com.osrstcg.party.TcgTradeInvitePartyMessage;
import com.osrstcg.party.TcgTradeInviteResponsePartyMessage;
import com.osrstcg.party.TcgTradeOfferCardDto;
import com.osrstcg.party.TcgTradeOfferDeltaPartyMessage;
import com.osrstcg.party.TcgTradeReadyPartyMessage;
import com.osrstcg.ui.collectionalbum.AlbumInstanceTooltip;
import com.osrstcg.ui.collectionalbum.CollectionAlbumManager;
import com.osrstcg.ui.trade.TradeWindowManager;
import com.osrstcg.util.TcgPluginGameMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;

/**
 * Two-sided party card trade: invite → accept → delta offers → both ready → commit leader swaps cards.
 */
@Slf4j
@Singleton
public class CardPartyTradeService
{
	static final long INVITE_TTL_MS = 60_000L;
	/** How long the sender waits for an invite delivery ack before treating the send as failed. */
	static final long INVITE_ACK_TIMEOUT_MS = 10_000L;
	/** Cooldown after sending a trade request before another can be sent. */
	static final long INVITE_SEND_COOLDOWN_MS = 10_000L;
	static final int MAX_OFFERS_PER_SIDE = 28;

	static final int REJECT_NONE = 0;
	static final int REJECT_TUNING_MISMATCH = 1;
	static final int REJECT_DEBUG_MISMATCH = 2;
	static final int REJECT_SENDER_TOO_OLD = 3;
	static final int REJECT_BAD_PAYLOAD = 4;
	static final int REJECT_BUSY = 5;

	private final PartyService partyService;
	private final TcgStateService stateService;
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final PackRevealSoundService packRevealSoundService;
	private final CardPartyTransferService cardPartyTransferService;
	private final Provider<CollectionAlbumManager> collectionAlbumManagerProvider;
	private final Provider<TradeWindowManager> tradeWindowManagerProvider;

	private final Object lock = new Object();
	private PendingInboundInvite pendingInbound;
	private PendingOutboundInvite pendingOutbound;
	/** One-shot status text for the album UI (e.g. invite TTL expiry). */
	private String pendingStatusMessage;
	private TradeSession session;
	private long lastInviteSendAtMs;
	private final List<Runnable> uiListeners = new CopyOnWriteArrayList<>();
	private int tickCounter;
	private final java.util.Set<String> processedCommitTradeIds =
		java.util.Collections.synchronizedSet(new java.util.HashSet<>());

	public static final class PendingInboundInvite
	{
		private final String tradeId;
		private final long fromMemberId;
		private final String fromDisplayName;
		private final long createdAtMs;
		private final RewardTuningState partnerTuning;
		private final boolean partnerDebugLogging;

		PendingInboundInvite(
			String tradeId,
			long fromMemberId,
			String fromDisplayName,
			long createdAtMs,
			RewardTuningState partnerTuning,
			boolean partnerDebugLogging)
		{
			this.tradeId = tradeId;
			this.fromMemberId = fromMemberId;
			this.fromDisplayName = fromDisplayName;
			this.createdAtMs = createdAtMs;
			this.partnerTuning = partnerTuning;
			this.partnerDebugLogging = partnerDebugLogging;
		}

		public String getTradeId()
		{
			return tradeId;
		}

		public long getFromMemberId()
		{
			return fromMemberId;
		}

		public String getFromDisplayName()
		{
			return fromDisplayName;
		}
	}

	private static final class PendingOutboundInvite
	{
		private final String tradeId;
		private final long recipientMemberId;
		private final String recipientDisplayName;
		private final long createdAtMs;
		private boolean acked;

		PendingOutboundInvite(String tradeId, long recipientMemberId, String recipientDisplayName, long createdAtMs)
		{
			this.tradeId = tradeId;
			this.recipientMemberId = recipientMemberId;
			this.recipientDisplayName = recipientDisplayName;
			this.createdAtMs = createdAtMs;
		}
	}

	public static final class PendingOutboundInviteView
	{
		private final String tradeId;
		private final long recipientMemberId;
		private final String recipientDisplayName;

		PendingOutboundInviteView(String tradeId, long recipientMemberId, String recipientDisplayName)
		{
			this.tradeId = tradeId;
			this.recipientMemberId = recipientMemberId;
			this.recipientDisplayName = recipientDisplayName;
		}

		public String getTradeId()
		{
			return tradeId;
		}

		public long getRecipientMemberId()
		{
			return recipientMemberId;
		}

		public String getRecipientDisplayName()
		{
			return recipientDisplayName;
		}
	}

	public static final class TradeOfferView
	{
		private final String cardInstanceId;
		private final String cardName;
		private final boolean foil;
		private final String pulledByUsername;
		private final long pulledAtEpochMs;

		TradeOfferView(String cardInstanceId, String cardName, boolean foil, String pulledByUsername, long pulledAtEpochMs)
		{
			this.cardInstanceId = cardInstanceId;
			this.cardName = cardName;
			this.foil = foil;
			this.pulledByUsername = pulledByUsername;
			this.pulledAtEpochMs = pulledAtEpochMs;
		}

		public String getCardInstanceId()
		{
			return cardInstanceId;
		}

		public String getCardName()
		{
			return cardName;
		}

		public boolean isFoil()
		{
			return foil;
		}

		public String getPulledByUsername()
		{
			return pulledByUsername;
		}

		public long getPulledAtEpochMs()
		{
			return pulledAtEpochMs;
		}
	}

	public static final class TradeSessionView
	{
		private final String tradeId;
		private final long partnerMemberId;
		private final String partnerDisplayName;
		private final String localDisplayName;
		private final List<TradeOfferView> localOffers;
		private final List<TradeOfferView> remoteOffers;
		private final boolean localReady;
		private final boolean remoteReady;

		TradeSessionView(
			String tradeId,
			long partnerMemberId,
			String partnerDisplayName,
			String localDisplayName,
			List<TradeOfferView> localOffers,
			List<TradeOfferView> remoteOffers,
			boolean localReady,
			boolean remoteReady)
		{
			this.tradeId = tradeId;
			this.partnerMemberId = partnerMemberId;
			this.partnerDisplayName = partnerDisplayName;
			this.localDisplayName = localDisplayName;
			this.localOffers = localOffers;
			this.remoteOffers = remoteOffers;
			this.localReady = localReady;
			this.remoteReady = remoteReady;
		}

		public String getTradeId()
		{
			return tradeId;
		}

		public long getPartnerMemberId()
		{
			return partnerMemberId;
		}

		public String getPartnerDisplayName()
		{
			return partnerDisplayName;
		}

		public String getLocalDisplayName()
		{
			return localDisplayName;
		}

		public List<TradeOfferView> getLocalOffers()
		{
			return localOffers;
		}

		public List<TradeOfferView> getRemoteOffers()
		{
			return remoteOffers;
		}

		public boolean isLocalReady()
		{
			return localReady;
		}

		public boolean isRemoteReady()
		{
			return remoteReady;
		}
	}

	private static final class TradeSession
	{
		private final String tradeId;
		private final long partnerMemberId;
		private String partnerDisplayName;
		private final RewardTuningState partnerTuning;
		private final boolean partnerDebugLogging;
		private final List<TradeOfferView> localOffers = new ArrayList<>();
		private final List<TradeOfferView> remoteOffers = new ArrayList<>();
		private boolean localReady;
		private boolean remoteReady;
		private boolean commitSent;
		private boolean closed;

		TradeSession(
			String tradeId,
			long partnerMemberId,
			String partnerDisplayName,
			RewardTuningState partnerTuning,
			boolean partnerDebugLogging)
		{
			this.tradeId = tradeId;
			this.partnerMemberId = partnerMemberId;
			this.partnerDisplayName = partnerDisplayName;
			this.partnerTuning = partnerTuning;
			this.partnerDebugLogging = partnerDebugLogging;
		}
	}

	@Inject
	public CardPartyTradeService(
		PartyService partyService,
		TcgStateService stateService,
		ClientThread clientThread,
		ChatMessageManager chatMessageManager,
		PackRevealSoundService packRevealSoundService,
		CardPartyTransferService cardPartyTransferService,
		Provider<CollectionAlbumManager> collectionAlbumManagerProvider,
		Provider<TradeWindowManager> tradeWindowManagerProvider)
	{
		this.partyService = partyService;
		this.stateService = stateService;
		this.clientThread = clientThread;
		this.chatMessageManager = chatMessageManager;
		this.packRevealSoundService = packRevealSoundService;
		this.cardPartyTransferService = cardPartyTransferService;
		this.collectionAlbumManagerProvider = collectionAlbumManagerProvider;
		this.tradeWindowManagerProvider = tradeWindowManagerProvider;
	}

	public void addUiListener(Runnable listener)
	{
		if (listener != null)
		{
			uiListeners.add(listener);
		}
	}

	public void removeUiListener(Runnable listener)
	{
		uiListeners.remove(listener);
	}

	public PendingInboundInvite getPendingInboundInvite()
	{
		synchronized (lock)
		{
			return pendingInbound;
		}
	}

	public PendingOutboundInviteView getPendingOutboundInvite()
	{
		synchronized (lock)
		{
			if (pendingOutbound == null)
			{
				return null;
			}
			return new PendingOutboundInviteView(
				pendingOutbound.tradeId,
				pendingOutbound.recipientMemberId,
				pendingOutbound.recipientDisplayName);
		}
	}

	public boolean hasPendingOutboundInvite()
	{
		return getPendingOutboundInvite() != null;
	}

	/**
	 * Returns and clears a one-shot status message for the album south bar (e.g. invite expired).
	 */
	public String consumePendingStatusMessage()
	{
		synchronized (lock)
		{
			String msg = pendingStatusMessage;
			pendingStatusMessage = null;
			return msg;
		}
	}

	public boolean hasPendingInboundInvite()
	{
		return getPendingInboundInvite() != null;
	}

	/** True while a send-trade cooldown is active after the last invite send. */
	public boolean isTradeInviteOnCooldown()
	{
		return getTradeInviteCooldownRemainingMs() > 0L;
	}

	/** Milliseconds left on the send-trade cooldown, or 0 when ready. */
	public long getTradeInviteCooldownRemainingMs()
	{
		synchronized (lock)
		{
			return getTradeInviteCooldownRemainingMsUnlocked();
		}
	}

	public boolean isTradeActive()
	{
		synchronized (lock)
		{
			return session != null && !session.closed;
		}
	}

	public boolean isBusy()
	{
		synchronized (lock)
		{
			return pendingInbound != null || pendingOutbound != null || (session != null && !session.closed);
		}
	}

	public TradeSessionView getSessionView()
	{
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return null;
			}
			PartyMember local = partyService.getLocalMember();
			String localName = displayNameOf(local, "You");
			return new TradeSessionView(
				session.tradeId,
				session.partnerMemberId,
				session.partnerDisplayName,
				localName,
				List.copyOf(session.localOffers),
				List.copyOf(session.remoteOffers),
				session.localReady,
				session.remoteReady);
		}
	}

	public boolean isInstanceOfferedLocally(String cardInstanceId)
	{
		if (cardInstanceId == null || cardInstanceId.isEmpty())
		{
			return false;
		}
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return false;
			}
			for (TradeOfferView o : session.localOffers)
			{
				if (cardInstanceId.equals(o.getCardInstanceId()))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String sendTradeInvite(long recipientMemberId)
	{
		String partyErr = validatePartyRecipient(recipientMemberId);
		if (partyErr != null)
		{
			return partyErr;
		}
		synchronized (lock)
		{
			long cooldownLeft = getTradeInviteCooldownRemainingMsUnlocked();
			if (cooldownLeft > 0L)
			{
				long secs = (cooldownLeft + 999L) / 1000L;
				return "Wait " + secs + "s before sending another trade request.";
			}
			if (isBusyUnlocked())
			{
				return "Finish or cancel your current trade first.";
			}
		}

		RewardTuningState tuning;
		boolean senderDebugLogging;
		synchronized (stateService)
		{
			tuning = stateService.getState().getRewardTuning();
			senderDebugLogging = stateService.isDebugLogging();
		}

		PartyMember recipient = partyService.getMemberById(recipientMemberId);
		String recipientName = displayNameOf(recipient, "Party member");

		String tradeId = UUID.randomUUID().toString();
		TcgTradeInvitePartyMessage m = new TcgTradeInvitePartyMessage();
		m.setTradeId(tradeId);
		m.setRecipientMemberId(recipientMemberId);
		m.setFoilChancePercent(tuning.getFoilChancePercent());
		m.setKillCreditMultiplier(tuning.getKillCreditMultiplier());
		m.setLevelUpCreditMultiplier(tuning.getLevelUpCreditMultiplier());
		m.setXpCreditMultiplier(tuning.getXpCreditMultiplier());
		m.setSenderDebugLogging(senderDebugLogging);

		synchronized (lock)
		{
			if (getTradeInviteCooldownRemainingMsUnlocked() > 0L)
			{
				long secs = (getTradeInviteCooldownRemainingMsUnlocked() + 999L) / 1000L;
				return "Wait " + secs + "s before sending another trade request.";
			}
			if (isBusyUnlocked())
			{
				return "Finish or cancel your current trade first.";
			}
			pendingOutbound = new PendingOutboundInvite(
				tradeId, recipientMemberId, recipientName, System.currentTimeMillis());
		}

		try
		{
			partyService.send(m);
		}
		catch (Exception ex)
		{
			synchronized (lock)
			{
				if (pendingOutbound != null && tradeId.equals(pendingOutbound.tradeId))
				{
					pendingOutbound = null;
				}
			}
			log.debug("Failed to send trade request", ex);
			notifyUi();
			return "Could not send trade request (party connection).";
		}
		synchronized (lock)
		{
			lastInviteSendAtMs = System.currentTimeMillis();
		}
		notifyUi();
		return null;
	}

	private long getTradeInviteCooldownRemainingMsUnlocked()
	{
		if (lastInviteSendAtMs <= 0L)
		{
			return 0L;
		}
		long remaining = INVITE_SEND_COOLDOWN_MS - (System.currentTimeMillis() - lastInviteSendAtMs);
		return Math.max(0L, remaining);
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String acceptPendingInvite()
	{
		PendingInboundInvite invite;
		synchronized (lock)
		{
			invite = pendingInbound;
			if (invite == null)
			{
				return "No pending trade request.";
			}
			if (session != null && !session.closed)
			{
				return "Already in a trade.";
			}
		}

		String partyErr = validatePartyRecipient(invite.fromMemberId);
		if (partyErr != null)
		{
			declinePendingInviteInternal(REJECT_BAD_PAYLOAD);
			return partyErr;
		}

		RewardTuningState mine;
		boolean localDebug;
		synchronized (stateService)
		{
			mine = stateService.getState().getRewardTuning();
			localDebug = stateService.isDebugLogging();
		}
		int parity = evaluateInviteParity(
			invite.partnerDebugLogging, localDebug, invite.partnerTuning, mine);
		if (parity != REJECT_NONE)
		{
			declinePendingInviteInternal(parity);
			return parityUserMessage(parity, true);
		}

		sendInviteResponse(invite.tradeId, invite.fromMemberId, true, REJECT_NONE, mine, localDebug);

		PartyMember from = partyService.getMemberById(invite.fromMemberId);
		String name = displayNameOf(from, invite.fromDisplayName);
		synchronized (lock)
		{
			pendingInbound = null;
			pendingOutbound = null;
			session = new TradeSession(
				invite.tradeId, invite.fromMemberId, name, invite.partnerTuning, invite.partnerDebugLogging);
		}
		openTradeWindow();
		notifyUi();
		return null;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String declinePendingInvite()
	{
		return declinePendingInviteInternal(REJECT_NONE);
	}

	private String declinePendingInviteInternal(int rejectCode)
	{
		PendingInboundInvite invite;
		synchronized (lock)
		{
			invite = pendingInbound;
			if (invite == null)
			{
				return "No pending trade request.";
			}
			pendingInbound = null;
		}
		sendInviteResponse(invite.tradeId, invite.fromMemberId, false,
			rejectCode == REJECT_NONE ? REJECT_BUSY : rejectCode, null, null);
		notifyUi();
		return null;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String offerCard(String cardInstanceId)
	{
		if (cardInstanceId == null || cardInstanceId.trim().isEmpty())
		{
			return "Select a card to offer.";
		}
		String id = cardInstanceId.trim();
		if (cardPartyTransferService.isInstancePendingGift(id))
		{
			return "That card copy is already being sent.";
		}

		OwnedCardInstance inst;
		synchronized (stateService)
		{
			inst = stateService.getState().getCollectionState().findInstanceById(id).orElse(null);
		}
		if (inst == null)
		{
			return "You do not own that card copy.";
		}
		if (inst.isLocked())
		{
			return AlbumInstanceTooltip.LOCKED_ACTION_HINT;
		}

		TradeOfferView offer = toView(inst);
		String tradeId;
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return "No active trade.";
			}
			if (session.localOffers.size() >= MAX_OFFERS_PER_SIDE)
			{
				return "Trade offer is full (" + MAX_OFFERS_PER_SIDE + " cards).";
			}
			if (findOfferIndex(session.localOffers, id) >= 0)
			{
				return "That card is already offered.";
			}
			session.localOffers.add(offer);
			clearReadyFlags(session);
			tradeId = session.tradeId;
		}

		TcgTradeOfferDeltaPartyMessage m = new TcgTradeOfferDeltaPartyMessage();
		m.setTradeId(tradeId);
		m.setAdd(true);
		m.setCardInstanceId(offer.getCardInstanceId());
		m.setCardName(offer.getCardName());
		m.setFoil(offer.isFoil());
		m.setPulledByUsername(offer.getPulledByUsername());
		m.setPulledAtEpochMs(offer.getPulledAtEpochMs());
		if (!sendParty(m, "Could not update trade offer (party connection)."))
		{
			synchronized (lock)
			{
				if (session != null && !session.closed)
				{
					int idx = findOfferIndex(session.localOffers, id);
					if (idx >= 0)
					{
						session.localOffers.remove(idx);
					}
				}
			}
			notifyUi();
			return "Could not update trade offer (party connection).";
		}
		notifyUi();
		return null;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String removeOfferedCard(String cardInstanceId)
	{
		if (cardInstanceId == null || cardInstanceId.trim().isEmpty())
		{
			return "Select a card to remove.";
		}
		String id = cardInstanceId.trim();
		TradeOfferView removed;
		String tradeId;
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return "No active trade.";
			}
			int idx = findOfferIndex(session.localOffers, id);
			if (idx < 0)
			{
				return "That card is not in your offer.";
			}
			removed = session.localOffers.remove(idx);
			clearReadyFlags(session);
			tradeId = session.tradeId;
		}

		TcgTradeOfferDeltaPartyMessage m = new TcgTradeOfferDeltaPartyMessage();
		m.setTradeId(tradeId);
		m.setAdd(false);
		m.setCardInstanceId(removed.getCardInstanceId());
		m.setCardName(removed.getCardName());
		m.setFoil(removed.isFoil());
		m.setPulledByUsername(removed.getPulledByUsername());
		m.setPulledAtEpochMs(removed.getPulledAtEpochMs());
		if (!sendParty(m, "Could not update trade offer (party connection)."))
		{
			synchronized (lock)
			{
				if (session != null && !session.closed)
				{
					session.localOffers.add(removed);
				}
			}
			notifyUi();
			return "Could not update trade offer (party connection).";
		}
		notifyUi();
		return null;
	}

	/**
	 * Marks local player ready. When both are ready, the lower member id sends commit.
	 *
	 * @return null on success, or user-facing error
	 */
	public String setLocalReady(boolean ready)
	{
		boolean shouldTryCommit = false;
		String tradeId;
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return "No active trade.";
			}
			session.localReady = ready;
			shouldTryCommit = ready && session.remoteReady && !session.commitSent;
			tradeId = session.tradeId;
		}

		TcgTradeReadyPartyMessage m = new TcgTradeReadyPartyMessage();
		m.setTradeId(tradeId);
		m.setReady(ready);
		if (!sendParty(m, "Could not update trade ready state (party connection)."))
		{
			synchronized (lock)
			{
				if (session != null && !session.closed)
				{
					session.localReady = !ready;
				}
			}
			notifyUi();
			return "Could not update trade ready state (party connection).";
		}
		notifyUi();
		if (shouldTryCommit)
		{
			trySendCommitAsLeader();
		}
		return null;
	}

	/**
	 * Cancels a pending outbound trade request (before the partner accepts).
	 *
	 * @return null on success, or user-facing error
	 */
	public String cancelPendingOutboundInvite()
	{
		String tradeId;
		synchronized (lock)
		{
			if (pendingOutbound == null)
			{
				return "No pending trade offer.";
			}
			tradeId = pendingOutbound.tradeId;
			pendingOutbound = null;
		}
		TcgTradeCancelPartyMessage m = new TcgTradeCancelPartyMessage();
		m.setTradeId(tradeId);
		sendParty(m, null);
		notifyUi();
		return null;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String cancelActiveTrade()
	{
		if (!isTradeActive() && hasPendingOutboundInvite())
		{
			return cancelPendingOutboundInvite();
		}

		String tradeId;
		synchronized (lock)
		{
			if (session == null || session.closed)
			{
				return "No active trade.";
			}
			tradeId = session.tradeId;
			closeSessionUnlocked("Trade cancelled.");
		}
		TcgTradeCancelPartyMessage m = new TcgTradeCancelPartyMessage();
		m.setTradeId(tradeId);
		sendParty(m, null);
		closeTradeWindow();
		notifyUi();
		return null;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		long now = System.currentTimeMillis();
		boolean changed = false;
		String expiredOutboundTradeId = null;
		String failedAckTradeId = null;
		String failedAckName = null;
		synchronized (lock)
		{
			if (pendingOutbound != null && !pendingOutbound.acked
				&& now - pendingOutbound.createdAtMs > INVITE_ACK_TIMEOUT_MS)
			{
				failedAckTradeId = pendingOutbound.tradeId;
				failedAckName = pendingOutbound.recipientDisplayName;
				pendingOutbound = null;
				pendingStatusMessage = "Failed to send trade request to " + failedAckName + ".";
				changed = true;
			}
		}
		if (failedAckTradeId != null)
		{
			TcgTradeCancelPartyMessage m = new TcgTradeCancelPartyMessage();
			m.setTradeId(failedAckTradeId);
			sendParty(m, null);
			notifyUi();
		}

		if (++tickCounter % 20 != 0)
		{
			return;
		}
		synchronized (lock)
		{
			if (pendingInbound != null && now - pendingInbound.createdAtMs > INVITE_TTL_MS)
			{
				pendingInbound = null;
				changed = true;
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Trade request timed out.");
			}
			if (pendingOutbound != null && now - pendingOutbound.createdAtMs > INVITE_TTL_MS)
			{
				expiredOutboundTradeId = pendingOutbound.tradeId;
				pendingOutbound = null;
				pendingStatusMessage = "Trade request expired.";
				changed = true;
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Trade request expired.");
			}
			if (session != null && !session.closed)
			{
				PartyMember partner = partyService.getMemberById(session.partnerMemberId);
				if (!partyService.isInParty() || partner == null)
				{
					closeSessionUnlocked("Trade cancelled: partner left the party.");
					changed = true;
				}
			}
			synchronized (processedCommitTradeIds)
			{
				if (processedCommitTradeIds.size() > 600)
				{
					processedCommitTradeIds.clear();
				}
			}
		}
		if (expiredOutboundTradeId != null)
		{
			TcgTradeCancelPartyMessage m = new TcgTradeCancelPartyMessage();
			m.setTradeId(expiredOutboundTradeId);
			sendParty(m, null);
		}
		if (changed)
		{
			closeTradeWindow();
			notifyUi();
		}
	}

	@Subscribe
	public void onTcgTradeInvitePartyMessage(TcgTradeInvitePartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getRecipientMemberId() != local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleInviteOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeInviteResponsePartyMessage(TcgTradeInviteResponsePartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getOriginalSenderMemberId() != local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleInviteResponseOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeInviteAckPartyMessage(TcgTradeInviteAckPartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getMemberId() == local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleInviteAckOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeOfferDeltaPartyMessage(TcgTradeOfferDeltaPartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getMemberId() == local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleOfferDeltaOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeReadyPartyMessage(TcgTradeReadyPartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getMemberId() == local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleReadyOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeCancelPartyMessage(TcgTradeCancelPartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getMemberId() == local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleCancelOnClientThread(msg));
	}

	@Subscribe
	public void onTcgTradeCommitPartyMessage(TcgTradeCommitPartyMessage msg)
	{
		if (msg == null || msg.getTradeId() == null || msg.getTradeId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getMemberId() == local.getMemberId())
		{
			return;
		}
		clientThread.invokeLater(() -> handleCommitOnClientThread(msg));
	}

	private void handleInviteOnClientThread(TcgTradeInvitePartyMessage msg)
	{
		long fromId = msg.getMemberId();
		PartyMember from = partyService.getMemberById(fromId);
		String who = displayNameOf(from, "Party member");

		if (msg.getTradeId() == null || msg.getTradeId().trim().isEmpty())
		{
			sendInviteResponse(msg.getTradeId(), fromId, false, REJECT_BAD_PAYLOAD, null, null);
			return;
		}

		RewardTuningState senderTuning = tuningFromInvite(msg);
		RewardTuningState mine = stateService.getState().getRewardTuning();
		int parity = evaluateInviteParity(
			msg.getSenderDebugLogging(), stateService.isDebugLogging(), senderTuning, mine);
		if (parity != REJECT_NONE)
		{
			sendInviteResponse(msg.getTradeId(), fromId, false, parity, null, null);
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, parityUserMessage(parity, true));
			return;
		}

		synchronized (lock)
		{
			if (isBusyUnlocked())
			{
				sendInviteResponse(msg.getTradeId(), fromId, false, REJECT_BUSY, null, null);
				return;
			}
			pendingInbound = new PendingInboundInvite(
				msg.getTradeId().trim(),
				fromId,
				who,
				System.currentTimeMillis(),
				senderTuning,
				msg.getSenderDebugLogging().booleanValue());
		}

		sendInviteAck(msg.getTradeId().trim());

		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
			who + " wants to trade! Accept the trade request through the album window.");
		refreshAlbumPartyTradeUi();
		notifyUi();
	}

	private void handleInviteAckOnClientThread(TcgTradeInviteAckPartyMessage msg)
	{
		synchronized (lock)
		{
			if (pendingOutbound == null || !pendingOutbound.tradeId.equals(msg.getTradeId()))
			{
				return;
			}
			pendingOutbound.acked = true;
		}
		notifyUi();
	}

	private void handleInviteResponseOnClientThread(TcgTradeInviteResponsePartyMessage msg)
	{
		PendingOutboundInvite outbound;
		synchronized (lock)
		{
			outbound = pendingOutbound;
			if (outbound == null || !outbound.tradeId.equals(msg.getTradeId()))
			{
				return;
			}
			// Any response proves delivery; keep pending only until we finish handling rejects/accept.
			outbound.acked = true;
			pendingOutbound = null;
		}

		PartyMember responder = partyService.getMemberById(msg.getMemberId());
		String target = displayNameOf(responder, "Party member");

		if (!msg.isAccepted())
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				parityRejectChatForSender(msg.getRejectCode(), target));
			notifyUi();
			return;
		}

		RewardTuningState partnerTuning = tuningFromInviteResponse(msg);
		RewardTuningState mine = stateService.getState().getRewardTuning();
		int parity = evaluateInviteParity(
			msg.getResponderDebugLogging(), stateService.isDebugLogging(), partnerTuning, mine);
		if (parity != REJECT_NONE)
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				parityRejectChatForSender(parity, target));
			TcgTradeCancelPartyMessage cancel = new TcgTradeCancelPartyMessage();
			cancel.setTradeId(msg.getTradeId());
			sendParty(cancel, null);
			notifyUi();
			return;
		}

		synchronized (lock)
		{
			session = new TradeSession(
				msg.getTradeId(),
				msg.getMemberId(),
				target,
				partnerTuning,
				msg.getResponderDebugLogging().booleanValue());
		}
		openTradeWindow();
		notifyUi();
	}

	private void handleOfferDeltaOnClientThread(TcgTradeOfferDeltaPartyMessage msg)
	{
		synchronized (lock)
		{
			if (session == null || session.closed || !session.tradeId.equals(msg.getTradeId()))
			{
				return;
			}
			if (msg.getMemberId() != session.partnerMemberId)
			{
				return;
			}
			String id = msg.getCardInstanceId() == null ? "" : msg.getCardInstanceId().trim();
			if (id.isEmpty())
			{
				return;
			}
			if (msg.isAdd())
			{
				if (findOfferIndex(session.remoteOffers, id) >= 0)
				{
					return;
				}
				if (session.remoteOffers.size() >= MAX_OFFERS_PER_SIDE)
				{
					return;
				}
				String name = msg.getCardName() == null ? "" : msg.getCardName().trim();
				session.remoteOffers.add(new TradeOfferView(
					id, name, msg.isFoil(),
					msg.getPulledByUsername() == null ? "" : msg.getPulledByUsername().trim(),
					Math.max(0L, msg.getPulledAtEpochMs())));
			}
			else
			{
				int idx = findOfferIndex(session.remoteOffers, id);
				if (idx >= 0)
				{
					session.remoteOffers.remove(idx);
				}
			}
			clearReadyFlags(session);
		}
		notifyUi();
	}

	private void handleReadyOnClientThread(TcgTradeReadyPartyMessage msg)
	{
		boolean shouldTryCommit = false;
		synchronized (lock)
		{
			if (session == null || session.closed || !session.tradeId.equals(msg.getTradeId()))
			{
				return;
			}
			if (msg.getMemberId() != session.partnerMemberId)
			{
				return;
			}
			session.remoteReady = msg.isReady();
			shouldTryCommit = session.localReady && session.remoteReady && !session.commitSent;
		}
		notifyUi();
		if (shouldTryCommit)
		{
			trySendCommitAsLeader();
		}
	}

	private void handleCancelOnClientThread(TcgTradeCancelPartyMessage msg)
	{
		boolean clearedInbound = false;
		boolean clearedSession = false;
		synchronized (lock)
		{
			if (pendingInbound != null && pendingInbound.tradeId.equals(msg.getTradeId()))
			{
				pendingInbound = null;
				clearedInbound = true;
			}
			if (session != null && !session.closed && session.tradeId.equals(msg.getTradeId())
				&& msg.getMemberId() == session.partnerMemberId)
			{
				String who = session.partnerDisplayName == null || session.partnerDisplayName.isEmpty()
					? "Partner"
					: session.partnerDisplayName;
				closeSessionUnlocked(who + " cancelled the trade.");
				clearedSession = true;
			}
		}
		if (clearedInbound)
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Trade request was cancelled.");
		}
		if (clearedSession)
		{
			closeTradeWindow();
		}
		if (clearedInbound || clearedSession)
		{
			notifyUi();
		}
	}

	private void handleCommitOnClientThread(TcgTradeCommitPartyMessage msg)
	{
		TradeSession active;
		List<TradeOfferView> localCopy;
		List<TradeOfferView> remoteCopy;
		synchronized (lock)
		{
			if (session == null || session.closed || !session.tradeId.equals(msg.getTradeId()))
			{
				return;
			}
			if (msg.getMemberId() != session.partnerMemberId)
			{
				return;
			}
			active = session;
			localCopy = List.copyOf(session.localOffers);
			remoteCopy = List.copyOf(session.remoteOffers);
		}

		synchronized (processedCommitTradeIds)
		{
			if (!processedCommitTradeIds.add(msg.getTradeId()))
			{
				return;
			}
		}

		RewardTuningState committerTuning = tuningFromCommit(msg);
		RewardTuningState mine = stateService.getState().getRewardTuning();
		int parity = evaluateInviteParity(
			msg.getCommitterDebugLogging(), stateService.isDebugLogging(), committerTuning, mine);
		if (parity != REJECT_NONE)
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Trade failed: " + parityUserMessage(parity, false) + " No cards were transferred.");
			failCommitAndCancel(active, msg.getTradeId());
			return;
		}

		List<TcgTradeOfferCardDto> expectedLocal = msg.getPartnerOffers();
		List<TcgTradeOfferCardDto> expectedRemote = msg.getCommitterOffers();
		if (!offersMatch(localCopy, expectedLocal) || !offersMatch(remoteCopy, expectedRemote))
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
				"Trade failed: offer mismatch. No cards were transferred.");
			failCommitAndCancel(active, msg.getTradeId());
			return;
		}

		applySwap(localCopy, remoteCopy, active.partnerDisplayName);
	}

	private void failCommitAndCancel(TradeSession active, String tradeId)
	{
		synchronized (lock)
		{
			if (session == active)
			{
				closeSessionUnlocked(null);
			}
		}
		TcgTradeCancelPartyMessage cancel = new TcgTradeCancelPartyMessage();
		cancel.setTradeId(tradeId);
		sendParty(cancel, null);
		closeTradeWindow();
		notifyUi();
	}

	private void trySendCommitAsLeader()
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return;
		}

		RewardTuningState mine;
		boolean localDebug;
		synchronized (stateService)
		{
			mine = stateService.getState().getRewardTuning();
			localDebug = stateService.isDebugLogging();
		}

		String tradeId;
		List<TradeOfferView> localCopy;
		List<TradeOfferView> remoteCopy;
		String partnerName;
		synchronized (lock)
		{
			if (session == null || session.closed || session.commitSent)
			{
				return;
			}
			if (!session.localReady || !session.remoteReady)
			{
				return;
			}
			if (local.getMemberId() > session.partnerMemberId)
			{
				return;
			}

			int parity = evaluateInviteParity(
				session.partnerDebugLogging, localDebug, session.partnerTuning, mine);
			if (parity != REJECT_NONE)
			{
				String failTradeId = session.tradeId;
				closeSessionUnlocked("Trade cancelled: " + parityUserMessage(parity, false));
				TcgTradeCancelPartyMessage cancel = new TcgTradeCancelPartyMessage();
				cancel.setTradeId(failTradeId);
				sendParty(cancel, null);
				closeTradeWindow();
				notifyUi();
				return;
			}

			session.commitSent = true;
			tradeId = session.tradeId;
			localCopy = List.copyOf(session.localOffers);
			remoteCopy = List.copyOf(session.remoteOffers);
			partnerName = session.partnerDisplayName;
		}

		TcgTradeCommitPartyMessage m = new TcgTradeCommitPartyMessage();
		m.setTradeId(tradeId);
		m.setCommitterOffers(toDtos(localCopy));
		m.setPartnerOffers(toDtos(remoteCopy));
		m.setFoilChancePercent(mine.getFoilChancePercent());
		m.setKillCreditMultiplier(mine.getKillCreditMultiplier());
		m.setLevelUpCreditMultiplier(mine.getLevelUpCreditMultiplier());
		m.setXpCreditMultiplier(mine.getXpCreditMultiplier());
		m.setCommitterDebugLogging(localDebug);

		synchronized (processedCommitTradeIds)
		{
			processedCommitTradeIds.add(tradeId);
		}

		if (!sendParty(m, "Could not complete trade (party connection)."))
		{
			synchronized (lock)
			{
				if (session != null && tradeId.equals(session.tradeId))
				{
					session.commitSent = false;
				}
			}
			synchronized (processedCommitTradeIds)
			{
				processedCommitTradeIds.remove(tradeId);
			}
			notifyUi();
			return;
		}

		applySwap(localCopy, remoteCopy, partnerName);
	}

	private void applySwap(List<TradeOfferView> giveAway, List<TradeOfferView> receive, String partnerName)
	{
		for (TradeOfferView o : giveAway)
		{
			stateService.removeCardInstance(o.getCardInstanceId());
		}
		for (TradeOfferView o : receive)
		{
			stateService.addOwnedCardInstance(OwnedCardInstance.createNew(
				o.getCardName(), o.isFoil(), o.getPulledByUsername(), o.getPulledAtEpochMs()));
		}

		synchronized (lock)
		{
			if (session != null)
			{
				session.closed = true;
				session = null;
			}
		}

		packRevealSoundService.playTransferSuccess();
		int given = giveAway.size();
		int got = receive.size();
		String body = String.format(Locale.US,
			"Trade with %s complete (%d given, %d received).",
			partnerName == null || partnerName.isEmpty() ? "party member" : partnerName,
			given, got);
		TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, body);
		refreshAlbum();
		closeTradeWindow();
		notifyUi();
	}

	static boolean offersMatch(List<TradeOfferView> localView, List<TcgTradeOfferCardDto> wire)
	{
		List<TcgTradeOfferCardDto> a = wire == null ? List.of() : wire;
		if (localView.size() != a.size())
		{
			return false;
		}
		for (int i = 0; i < localView.size(); i++)
		{
			TradeOfferView left = localView.get(i);
			TcgTradeOfferCardDto right = a.get(i);
			if (right == null)
			{
				return false;
			}
			if (!Objects.equals(left.getCardInstanceId(), nullToEmpty(right.getCardInstanceId()))
				|| !Objects.equals(left.getCardName(), nullToEmpty(right.getCardName()))
				|| left.isFoil() != right.isFoil()
				|| !Objects.equals(left.getPulledByUsername(), nullToEmpty(right.getPulledByUsername()))
				|| left.getPulledAtEpochMs() != Math.max(0L, right.getPulledAtEpochMs()))
			{
				return false;
			}
		}
		return true;
	}

	static TradeOfferView tradeOfferViewForTest(
		String instanceId, String cardName, boolean foil, String pulledBy, long at)
	{
		return new TradeOfferView(instanceId, cardName, foil, pulledBy, at);
	}

	static int evaluateInviteParity(
		Boolean partnerDebugLogging,
		boolean localDebugLogging,
		RewardTuningState partnerTuning,
		RewardTuningState localTuning)
	{
		if (partnerDebugLogging == null)
		{
			return REJECT_SENDER_TOO_OLD;
		}
		if (partnerDebugLogging.booleanValue() != localDebugLogging)
		{
			return REJECT_DEBUG_MISMATCH;
		}
		if (localTuning == null || !localTuning.matchesPartnerTuning(partnerTuning))
		{
			return REJECT_TUNING_MISMATCH;
		}
		return REJECT_NONE;
	}

	private void closeSessionUnlocked(String chatMessage)
	{
		if (session != null)
		{
			session.closed = true;
			session = null;
		}
		if (chatMessage != null && !chatMessage.isEmpty())
		{
			TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, chatMessage);
		}
	}

	private boolean isBusyUnlocked()
	{
		return pendingInbound != null || pendingOutbound != null || (session != null && !session.closed);
	}

	private static void clearReadyFlags(TradeSession session)
	{
		session.localReady = false;
		session.remoteReady = false;
		session.commitSent = false;
	}

	private static int findOfferIndex(List<TradeOfferView> offers, String instanceId)
	{
		for (int i = 0; i < offers.size(); i++)
		{
			if (instanceId.equals(offers.get(i).getCardInstanceId()))
			{
				return i;
			}
		}
		return -1;
	}

	private static TradeOfferView toView(OwnedCardInstance inst)
	{
		return new TradeOfferView(
			inst.getInstanceId(),
			inst.getCardName() == null ? "" : inst.getCardName().trim(),
			inst.isFoil(),
			inst.getPulledByUsername() == null ? "" : inst.getPulledByUsername(),
			inst.getPulledAtEpochMs());
	}

	private static List<TcgTradeOfferCardDto> toDtos(List<TradeOfferView> offers)
	{
		List<TcgTradeOfferCardDto> out = new ArrayList<>(offers.size());
		for (TradeOfferView o : offers)
		{
			TcgTradeOfferCardDto d = new TcgTradeOfferCardDto();
			d.setCardInstanceId(o.getCardInstanceId());
			d.setCardName(o.getCardName());
			d.setFoil(o.isFoil());
			d.setPulledByUsername(o.getPulledByUsername());
			d.setPulledAtEpochMs(o.getPulledAtEpochMs());
			out.add(d);
		}
		return out;
	}

	private String validatePartyRecipient(long recipientMemberId)
	{
		if (!partyService.isInParty())
		{
			return "Join a RuneLite party first.";
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return "Party session not ready.";
		}
		if (recipientMemberId == local.getMemberId())
		{
			return "Choose a different party member.";
		}
		PartyMember recipient = partyService.getMemberById(recipientMemberId);
		if (recipient == null)
		{
			return "That player is not in your party.";
		}
		return null;
	}

	private void sendInviteAck(String tradeId)
	{
		try
		{
			TcgTradeInviteAckPartyMessage ack = new TcgTradeInviteAckPartyMessage();
			ack.setTradeId(tradeId);
			partyService.send(ack);
		}
		catch (Exception ex)
		{
			log.debug("Failed to send trade request ack", ex);
		}
	}

	private void sendInviteResponse(
		String tradeId,
		long originalSenderMemberId,
		boolean accepted,
		int rejectCode,
		RewardTuningState localTuning,
		Boolean localDebugLogging)
	{
		try
		{
			TcgTradeInviteResponsePartyMessage r = new TcgTradeInviteResponsePartyMessage();
			r.setTradeId(tradeId);
			r.setOriginalSenderMemberId(originalSenderMemberId);
			r.setAccepted(accepted);
			r.setRejectCode(accepted ? REJECT_NONE : rejectCode);
			if (accepted && localTuning != null)
			{
				r.setFoilChancePercent(localTuning.getFoilChancePercent());
				r.setKillCreditMultiplier(localTuning.getKillCreditMultiplier());
				r.setLevelUpCreditMultiplier(localTuning.getLevelUpCreditMultiplier());
				r.setXpCreditMultiplier(localTuning.getXpCreditMultiplier());
				r.setResponderDebugLogging(localDebugLogging);
			}
			partyService.send(r);
		}
		catch (Exception ex)
		{
			log.debug("Failed to send trade request response", ex);
		}
	}

	private boolean sendParty(net.runelite.client.party.messages.PartyMessage message, String failChat)
	{
		try
		{
			partyService.send(message);
			return true;
		}
		catch (Exception ex)
		{
			log.debug("Failed to send party trade message", ex);
			if (failChat != null)
			{
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager, failChat);
			}
			return false;
		}
	}

	private void openTradeWindow()
	{
		TradeWindowManager mgr = tradeWindowManagerProvider.get();
		if (mgr != null)
		{
			mgr.showOrBringToFront();
		}
	}

	private void closeTradeWindow()
	{
		TradeWindowManager mgr = tradeWindowManagerProvider.get();
		if (mgr != null)
		{
			mgr.hide();
		}
	}

	private void refreshAlbum()
	{
		CollectionAlbumManager mgr = collectionAlbumManagerProvider.get();
		if (mgr != null)
		{
			mgr.refreshIfVisible();
		}
	}

	private void refreshAlbumPartyTradeUi()
	{
		CollectionAlbumManager mgr = collectionAlbumManagerProvider.get();
		if (mgr != null)
		{
			mgr.refreshPartyTradeUiIfVisible();
		}
	}

	private void notifyUi()
	{
		for (Runnable r : uiListeners)
		{
			try
			{
				r.run();
			}
			catch (Exception ex)
			{
				log.debug("Trade UI listener failed", ex);
			}
		}
		refreshAlbumPartyTradeUi();
		TradeWindowManager mgr = tradeWindowManagerProvider.get();
		if (mgr != null)
		{
			mgr.refreshIfVisible();
		}
	}

	private static RewardTuningState tuningFromInvite(TcgTradeInvitePartyMessage msg)
	{
		return RewardTuningState.mergeSerialized(
			msg.getFoilChancePercent(),
			msg.getKillCreditMultiplier(),
			msg.getLevelUpCreditMultiplier(),
			msg.getXpCreditMultiplier());
	}

	private static RewardTuningState tuningFromInviteResponse(TcgTradeInviteResponsePartyMessage msg)
	{
		return RewardTuningState.mergeSerialized(
			msg.getFoilChancePercent(),
			msg.getKillCreditMultiplier(),
			msg.getLevelUpCreditMultiplier(),
			msg.getXpCreditMultiplier());
	}

	private static RewardTuningState tuningFromCommit(TcgTradeCommitPartyMessage msg)
	{
		return RewardTuningState.mergeSerialized(
			msg.getFoilChancePercent(),
			msg.getKillCreditMultiplier(),
			msg.getLevelUpCreditMultiplier(),
			msg.getXpCreditMultiplier());
	}

	static String parityUserMessage(int rejectCode, boolean inboundInvite)
	{
		if (rejectCode == REJECT_TUNING_MISMATCH)
		{
			return inboundInvite
				? "Trade request ignored: your foil / credit multipliers do not match the sender's."
				: "foil / credit multipliers do not match.";
		}
		if (rejectCode == REJECT_DEBUG_MISMATCH)
		{
			return inboundInvite
				? "Trade request ignored: Overview debug mode must match the sender's."
				: "Overview debug mode must match on both clients.";
		}
		if (rejectCode == REJECT_SENDER_TOO_OLD)
		{
			return inboundInvite
				? "Trade request ignored: sender's client did not report debug mode (update OSRS TCG on both sides)."
				: "update OSRS TCG to the same version on both clients.";
		}
		return inboundInvite ? "Trade request ignored." : "settings do not match.";
	}

	private static String parityRejectChatForSender(int code, String target)
	{
		if (code == REJECT_TUNING_MISMATCH)
		{
			return String.format(Locale.US,
				"%s could not open trade: foil / credit multipliers do not match.", target);
		}
		if (code == REJECT_DEBUG_MISMATCH)
		{
			return String.format(Locale.US,
				"%s could not open trade: Overview debug mode must match on both clients.", target);
		}
		if (code == REJECT_SENDER_TOO_OLD)
		{
			return String.format(Locale.US,
				"%s could not open trade: update OSRS TCG to the same version on both clients.", target);
		}
		if (code == REJECT_BUSY)
		{
			return String.format(Locale.US, "%s declined the trade request.", target);
		}
		return String.format(Locale.US, "%s could not open trade.", target);
	}

	private static String displayNameOf(PartyMember member, String fallback)
	{
		if (member != null && member.getDisplayName() != null && !member.getDisplayName().trim().isEmpty())
		{
			return member.getDisplayName().trim();
		}
		return fallback == null || fallback.isEmpty() ? "Party member" : fallback;
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s.trim();
	}
}
