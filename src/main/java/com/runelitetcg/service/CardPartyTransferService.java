package com.runelitetcg.service;

import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.party.TcgCardGiftPartyMessage;
import com.runelitetcg.party.TcgCardGiftResponsePartyMessage;
import com.runelitetcg.ui.collectionalbum.CollectionAlbumManager;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;

/**
 * Party card gifting: sender offers (no removal yet); recipient validates multiplier parity, adds the card, then
 * sends {@link TcgCardGiftResponsePartyMessage}; sender removes one copy only when accepted.
 */
@Slf4j
@Singleton
public class CardPartyTransferService
{
	private static final long PENDING_TTL_MS = 90_000L;

	private final PartyService partyService;
	private final TcgStateService stateService;
	private final Client client;
	private final Provider<CollectionAlbumManager> collectionAlbumManagerProvider;

	private final java.util.Map<String, PendingOffer> pendingOffers = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> processedGiftTransferIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	private int tickCounter;

	private static final class PendingOffer
	{
		private final String cardName;
		private final boolean foil;
		private final long createdAtMs;

		private PendingOffer(String cardName, boolean foil, long createdAtMs)
		{
			this.cardName = cardName;
			this.foil = foil;
			this.createdAtMs = createdAtMs;
		}
	}

	@Inject
	public CardPartyTransferService(
		PartyService partyService,
		TcgStateService stateService,
		Client client,
		Provider<CollectionAlbumManager> collectionAlbumManagerProvider)
	{
		this.partyService = partyService;
		this.stateService = stateService;
		this.client = client;
		this.collectionAlbumManagerProvider = collectionAlbumManagerProvider;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String sendGift(long recipientMemberId, String cardName, boolean foil)
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
		if (cardName == null || cardName.trim().isEmpty())
		{
			return "Select a card to send.";
		}
		String name = cardName.trim();
		synchronized (stateService)
		{
			int owned = stateService.getState().getCollectionState().getOwnedCards()
				.getOrDefault(new com.runelitetcg.model.CardCollectionKey(name, foil), 0);
			if (owned < 1)
			{
				return "You do not own that card variant.";
			}
		}

		RewardTuningState tuning = stateService.getState().getRewardTuning();
		String transferId = java.util.UUID.randomUUID().toString();
		pendingOffers.put(transferId, new PendingOffer(name, foil, System.currentTimeMillis()));

		try
		{
			TcgCardGiftPartyMessage m = new TcgCardGiftPartyMessage();
			m.setRecipientMemberId(recipientMemberId);
			m.setCardName(name);
			m.setFoil(foil);
			m.setFoilChancePercent(tuning.getFoilChancePercent());
			m.setKillCreditMultiplier(tuning.getKillCreditMultiplier());
			m.setLevelUpCreditMultiplier(tuning.getLevelUpCreditMultiplier());
			m.setXpCreditMultiplier(tuning.getXpCreditMultiplier());
			m.setTransferId(transferId);
			partyService.send(m);
		}
		catch (Exception ex)
		{
			pendingOffers.remove(transferId);
			log.debug("Failed to send party card gift", ex);
			return "Could not send (party connection).";
		}
		return null;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (++tickCounter % 20 != 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		for (String tid : new java.util.ArrayList<>(pendingOffers.keySet()))
		{
			PendingOffer p = pendingOffers.get(tid);
			if (p != null && now - p.createdAtMs > PENDING_TTL_MS && pendingOffers.remove(tid, p))
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] Card send timed out (no response from recipient).", null);
			}
		}
		synchronized (processedGiftTransferIds)
		{
			if (processedGiftTransferIds.size() > 600)
			{
				processedGiftTransferIds.clear();
			}
		}
	}

	@Subscribe
	public void onTcgCardGiftPartyMessage(TcgCardGiftPartyMessage msg)
	{
		if (msg == null || msg.getTransferId() == null || msg.getTransferId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getRecipientMemberId() != local.getMemberId())
		{
			return;
		}

		synchronized (processedGiftTransferIds)
		{
			if (processedGiftTransferIds.contains(msg.getTransferId()))
			{
				return;
			}
		}

		RewardTuningState senderTuning = tuningFromGift(msg);
		RewardTuningState mine = stateService.getState().getRewardTuning();
		boolean tuningOk = mine.matchesPartnerTuning(senderTuning);
		long originalSender = msg.getMemberId();
		String card = msg.getCardName() == null ? "" : msg.getCardName().trim();
		boolean foil = msg.isFoil();

		if (!tuningOk)
		{
			sendResponse(msg.getTransferId(), originalSender, false);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[OSRS TCG] Incoming card ignored: your foil / credit multipliers do not match the sender's.", null);
			return;
		}

		stateService.addCard(card, foil, 1);
		synchronized (processedGiftTransferIds)
		{
			processedGiftTransferIds.add(msg.getTransferId());
		}
		sendResponse(msg.getTransferId(), originalSender, true);

		PartyMember from = partyService.getMemberById(originalSender);
		String who = from != null && from.getDisplayName() != null && !from.getDisplayName().trim().isEmpty()
			? from.getDisplayName().trim()
			: "Party member";
		String quotedCards = formatReceivedGiftCardList(card, foil);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			String.format("[OSRS TCG] %s just sent you %s !", who, quotedCards),
			null);
		refreshAlbumIfOpen();
	}

	private static String formatReceivedGiftCardList(String cardName, boolean foil)
	{
		String c = cardName == null ? "" : cardName.trim();
		if (c.isEmpty())
		{
			c = "Unknown card";
		}
		String one = foil ? c + " (foil)" : c;
		return "'" + one + "'";
	}

	private void refreshAlbumIfOpen()
	{
		CollectionAlbumManager mgr = collectionAlbumManagerProvider.get();
		if (mgr != null)
		{
			mgr.refreshIfVisible();
		}
	}

	@Subscribe
	public void onTcgCardGiftResponsePartyMessage(TcgCardGiftResponsePartyMessage msg)
	{
		if (msg == null || msg.getTransferId() == null || msg.getTransferId().isEmpty())
		{
			return;
		}
		PartyMember local = partyService.getLocalMember();
		if (local == null || msg.getOriginalSenderMemberId() != local.getMemberId())
		{
			return;
		}
		PendingOffer pending = pendingOffers.remove(msg.getTransferId());
		if (pending == null)
		{
			return;
		}

		PartyMember responder = partyService.getMemberById(msg.getMemberId());
		String target = responder != null && responder.getDisplayName() != null && !responder.getDisplayName().trim().isEmpty()
			? responder.getDisplayName().trim()
			: "Party member";

		if (msg.isAccepted())
		{
			boolean removed = stateService.removeCardQuantity(pending.cardName, pending.foil, 1);
			if (!removed)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[OSRS TCG] Recipient accepted the card but you no longer had that copy; check your collection.", null);
			}
			else
			{
				String foilTag = pending.foil ? " (foil)" : "";
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					String.format("[OSRS TCG] Sent %s%s to %s.", pending.cardName, foilTag, target),
					null);
			}
		}
		else
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				String.format("[OSRS TCG] %s could not accept the card (multiplier mismatch). You still have it.", target),
				null);
		}
		refreshAlbumIfOpen();
	}

	private void sendResponse(String transferId, long originalSenderMemberId, boolean accepted)
	{
		try
		{
			TcgCardGiftResponsePartyMessage r = new TcgCardGiftResponsePartyMessage();
			r.setTransferId(transferId);
			r.setOriginalSenderMemberId(originalSenderMemberId);
			r.setAccepted(accepted);
			partyService.send(r);
		}
		catch (Exception ex)
		{
			log.debug("Failed to send card gift response", ex);
		}
	}

	private static RewardTuningState tuningFromGift(TcgCardGiftPartyMessage msg)
	{
		return RewardTuningState.mergeSerialized(
			msg.getFoilChancePercent(),
			msg.getKillCreditMultiplier(),
			msg.getLevelUpCreditMultiplier(),
			msg.getXpCreditMultiplier());
	}
}
