package com.osrstcg.service;

import com.osrstcg.model.RewardTuningState;
import com.osrstcg.party.TcgTradeOfferCardDto;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CardPartyTradeServiceTest
{
	@Test
	public void offersMatchRequiresSameOrderAndFields()
	{
		CardPartyTradeService.TradeOfferView local =
			CardPartyTradeService.tradeOfferViewForTest("id-1", "Abyssal whip", false, "Player", 1000L);
		TcgTradeOfferCardDto wire = new TcgTradeOfferCardDto();
		wire.setCardInstanceId("id-1");
		wire.setCardName("Abyssal whip");
		wire.setFoil(false);
		wire.setPulledByUsername("Player");
		wire.setPulledAtEpochMs(1000L);

		Assert.assertTrue(CardPartyTradeService.offersMatch(List.of(local), List.of(wire)));

		wire.setFoil(true);
		Assert.assertFalse(CardPartyTradeService.offersMatch(List.of(local), List.of(wire)));
	}

	@Test
	public void offersMatchRejectsSizeMismatch()
	{
		CardPartyTradeService.TradeOfferView local =
			CardPartyTradeService.tradeOfferViewForTest("id-1", "Abyssal whip", false, "Player", 1000L);
		Assert.assertFalse(CardPartyTradeService.offersMatch(List.of(local), List.of()));
		Assert.assertTrue(CardPartyTradeService.offersMatch(List.of(), List.of()));
	}

	@Test
	public void evaluateInviteParityRejectsMissingDebug()
	{
		int code = CardPartyTradeService.evaluateInviteParity(
			null, false, RewardTuningState.DEFAULTS, RewardTuningState.DEFAULTS);
		Assert.assertEquals(CardPartyTradeService.REJECT_SENDER_TOO_OLD, code);
	}

	@Test
	public void evaluateInviteParityRejectsDebugMismatch()
	{
		int code = CardPartyTradeService.evaluateInviteParity(
			true, false, RewardTuningState.DEFAULTS, RewardTuningState.DEFAULTS);
		Assert.assertEquals(CardPartyTradeService.REJECT_DEBUG_MISMATCH, code);
	}

	@Test
	public void evaluateInviteParityRejectsTuningMismatch()
	{
		RewardTuningState other = new RewardTuningState(5, 1.0d, 1.0d, 1.0d);
		int code = CardPartyTradeService.evaluateInviteParity(
			false, false, other, RewardTuningState.DEFAULTS);
		Assert.assertEquals(CardPartyTradeService.REJECT_TUNING_MISMATCH, code);
	}

	@Test
	public void evaluateInviteParityAcceptsMatching()
	{
		int code = CardPartyTradeService.evaluateInviteParity(
			false, false, RewardTuningState.DEFAULTS, RewardTuningState.DEFAULTS);
		Assert.assertEquals(CardPartyTradeService.REJECT_NONE, code);
	}

	@Test
	public void parityUserMessageCoversTuningAndDebug()
	{
		Assert.assertTrue(CardPartyTradeService.parityUserMessage(
			CardPartyTradeService.REJECT_TUNING_MISMATCH, true).contains("multipliers"));
		Assert.assertTrue(CardPartyTradeService.parityUserMessage(
			CardPartyTradeService.REJECT_DEBUG_MISMATCH, true).contains("debug"));
	}
}
