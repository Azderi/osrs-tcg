package com.runelitetcg.service;

import com.runelitetcg.data.CardDatabase;
import com.runelitetcg.model.OwnedCardInstance;
import com.runelitetcg.model.RewardTuningState;
import com.runelitetcg.party.TcgCardGiftPartyMessage;
import com.runelitetcg.party.TcgCardGiftResponsePartyMessage;
import com.runelitetcg.ui.collectionalbum.CollectionAlbumManager;
import com.runelitetcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.util.Locale;
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
	private final ClientThread clientThread;
	private final ChatMessageManager chatMessageManager;
	private final PackRevealSoundService packRevealSoundService;
	private final CardDatabase cardDatabase;
	private final Provider<CollectionAlbumManager> collectionAlbumManagerProvider;

	private final java.util.Map<String, PendingOffer> pendingOffers = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> processedGiftTransferIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	private int tickCounter;

	private static final class PendingOffer
	{
		private final String cardName;
		private final boolean foil;
		private final String cardInstanceId;
		private final long createdAtMs;

		private PendingOffer(String cardName, boolean foil, String cardInstanceId, long createdAtMs)
		{
			this.cardName = cardName;
			this.foil = foil;
			this.cardInstanceId = cardInstanceId;
			this.createdAtMs = createdAtMs;
		}
	}

	@Inject
	public CardPartyTransferService(
		PartyService partyService,
		TcgStateService stateService,
		ClientThread clientThread,
		ChatMessageManager chatMessageManager,
		PackRevealSoundService packRevealSoundService,
		CardDatabase cardDatabase,
		Provider<CollectionAlbumManager> collectionAlbumManagerProvider)
	{
		this.partyService = partyService;
		this.stateService = stateService;
		this.clientThread = clientThread;
		this.chatMessageManager = chatMessageManager;
		this.packRevealSoundService = packRevealSoundService;
		this.cardDatabase = cardDatabase;
		this.collectionAlbumManagerProvider = collectionAlbumManagerProvider;
	}

	/**
	 * @return null on success, or user-facing error
	 */
	public String sendGift(long recipientMemberId, String cardName, boolean foil)
	{
		String partyErr = validateGiftPartyRecipient(recipientMemberId);
		if (partyErr != null)
		{
			return partyErr;
		}
		if (cardName == null || cardName.trim().isEmpty())
		{
			return "Select a card to send.";
		}
		String name = cardName.trim();
		OwnedCardInstance inst;
		synchronized (stateService)
		{
			java.util.Optional<OwnedCardInstance> pick = stateService.firstInstanceFifo(name, foil);
			if (pick.isEmpty())
			{
				return "You do not own that card variant.";
			}
			inst = pick.get();
		}
		return beginGiftTransfer(recipientMemberId, inst);
	}

	/**
	 * Sends the specific collection row (any copy the player owns).
	 *
	 * @return null on success, or user-facing error
	 */
	public String sendGift(long recipientMemberId, String cardInstanceId)
	{
		String partyErr = validateGiftPartyRecipient(recipientMemberId);
		if (partyErr != null)
		{
			return partyErr;
		}
		if (cardInstanceId == null || cardInstanceId.trim().isEmpty())
		{
			return "Select a card copy to send.";
		}
		OwnedCardInstance inst;
		synchronized (stateService)
		{
			inst = stateService.getState().getCollectionState()
				.findInstanceById(cardInstanceId.trim())
				.orElse(null);
		}
		if (inst == null)
		{
			return "You do not own that card copy.";
		}
		return beginGiftTransfer(recipientMemberId, inst);
	}

	private String validateGiftPartyRecipient(long recipientMemberId)
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

	private String beginGiftTransfer(long recipientMemberId, OwnedCardInstance inst)
	{
		if (inst == null)
		{
			return "Select a card to send.";
		}
		String name = inst.getCardName() == null ? "" : inst.getCardName().trim();
		if (name.isEmpty())
		{
			return "Select a card to send.";
		}
		boolean foil = inst.isFoil();
		String instanceId = inst.getInstanceId();
		RewardTuningState tuning;
		synchronized (stateService)
		{
			java.util.Optional<OwnedCardInstance> cur = stateService.getState().getCollectionState()
				.findInstanceById(instanceId);
			if (cur.isEmpty())
			{
				return "You do not own that card copy.";
			}
			inst = cur.get();
			tuning = stateService.getState().getRewardTuning();
		}

		String transferId = java.util.UUID.randomUUID().toString();
		pendingOffers.put(transferId, new PendingOffer(name, foil, inst.getInstanceId(), System.currentTimeMillis()));

		try
		{
			TcgCardGiftPartyMessage m = new TcgCardGiftPartyMessage();
			m.setRecipientMemberId(recipientMemberId);
			m.setCardName(name);
			m.setFoil(foil);
			m.setCardInstanceId(inst.getInstanceId());
			m.setPulledByUsername(inst.getPulledByUsername());
			m.setPulledAtEpochMs(inst.getPulledAtEpochMs());
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
				TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
					"Card send timed out (no response from recipient).");
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

		clientThread.invokeLater(() -> handleCardGiftPartyMessageOnClientThread(msg));
	}

	private void handleCardGiftPartyMessageOnClientThread(TcgCardGiftPartyMessage msg)
	{
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
		String senderInstanceId = msg.getCardInstanceId() == null ? "" : msg.getCardInstanceId().trim();

		if (senderInstanceId.isEmpty())
		{
			sendResponse(msg.getTransferId(), originalSender, false);
			TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
				"Incoming card ignored: incompatible gift message (missing instance id).");
			return;
		}

		if (!tuningOk)
		{
			sendResponse(msg.getTransferId(), originalSender, false);
			TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
				"Incoming card ignored: your foil / credit multipliers do not match the sender's.");
			return;
		}

		String by = msg.getPulledByUsername() == null ? "" : msg.getPulledByUsername().trim();
		long at = Math.max(0L, msg.getPulledAtEpochMs());
		stateService.addOwnedCardInstance(OwnedCardInstance.createNew(card, foil, by, at));
		synchronized (processedGiftTransferIds)
		{
			processedGiftTransferIds.add(msg.getTransferId());
		}
		sendResponse(msg.getTransferId(), originalSender, true);

		packRevealSoundService.playTransferSuccess();

		PartyMember from = partyService.getMemberById(originalSender);
		String who = from != null && from.getDisplayName() != null && !from.getDisplayName().trim().isEmpty()
			? from.getDisplayName().trim()
			: "Party member";
		Color rarity = cardDatabase.chatRarityColorForCardName(card);
		String formatted = TcgPluginGameMessages.formatGoldPrefixedSomeoneSentYou(who, card, foil, rarity);
		String plain = TcgPluginGameMessages.plainGoldPrefixedSomeoneSentYou(who, card, foil);
		TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
		refreshAlbumIfOpen();
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
		clientThread.invokeLater(() -> handleCardGiftResponseOnClientThread(msg));
	}

	private void handleCardGiftResponseOnClientThread(TcgCardGiftResponsePartyMessage msg)
	{
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
			boolean removed = stateService.removeCardInstance(pending.cardInstanceId);
			if (!removed)
			{
				TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
					"Recipient accepted the card but you no longer had that copy; check your collection.");
			}
			else
			{
				packRevealSoundService.playTransferSuccess();
				Color rarity = cardDatabase.chatRarityColorForCardName(pending.cardName);
				String formatted = TcgPluginGameMessages.formatGoldPrefixedYouSentCard(
					pending.cardName, pending.foil, target, rarity);
				String plain = TcgPluginGameMessages.plainGoldPrefixedYouSentCard(
					pending.cardName, pending.foil, target);
				TcgPluginGameMessages.queueFormattedGameMessage(chatMessageManager, formatted, plain);
			}
		}
		else
		{
			TcgPluginGameMessages.queueGoldPluginGameMessage(chatMessageManager,
				String.format(Locale.US, "%s could not accept the card. You still have it.", target));
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
