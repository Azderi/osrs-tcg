package com.osrstcg.state;

import java.util.Collections;
import java.util.List;
import lombok.Value;

/** Outcome of one pack-open request: credits before/after, price, and the pulled cards (or a failure message). */
@Value
public class PackOpenResult
{
	boolean success;
	String message;
	long creditsBefore;
	long creditsAfter;
	int packPrice;
	List<PackCardResult> pulls;
	String boosterDisplayName;
	/** Server pack {@code id}; drives overlay sleeve art from the pack catalog {@code image} URL. */
	String boosterPackId;
	/** True when pulls used apex rules (top three display tiers + boosted foils). */
	boolean apexPack;

	/** Builds a failure result; credits are unchanged and there are no pulls. */
	public static PackOpenResult failed(String message, long creditsBefore, int packPrice)
	{
		return new PackOpenResult(false, message, creditsBefore, creditsBefore, packPrice,
			Collections.emptyList(), null, null, false);
	}

	/** Builds a success result with the given pulls; a null pulls list is normalized to empty. */
	public static PackOpenResult succeeded(String message, long creditsBefore, long creditsAfter, int packPrice,
		List<PackCardResult> pulls, String boosterDisplayName, String boosterPackId, boolean apexPack)
	{
		return new PackOpenResult(true, message, creditsBefore, creditsAfter, packPrice,
			pulls == null ? Collections.emptyList() : pulls,
			boosterDisplayName,
			boosterPackId,
			apexPack);
	}
}
