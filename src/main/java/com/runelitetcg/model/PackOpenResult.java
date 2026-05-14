package com.runelitetcg.model;

import java.util.Collections;
import java.util.List;
import lombok.Value;

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
	/** Packs.json {@code id}; drives overlay art {@code /Pack_{Title}.png}. */
	String boosterPackId;

	public static PackOpenResult failed(String message, long creditsBefore, int packPrice)
	{
		return new PackOpenResult(false, message, creditsBefore, creditsBefore, packPrice,
			Collections.emptyList(), null, null);
	}

	public static PackOpenResult succeeded(String message, long creditsBefore, long creditsAfter, int packPrice,
		List<PackCardResult> pulls, String boosterDisplayName, String boosterPackId)
	{
		return new PackOpenResult(true, message, creditsBefore, creditsAfter, packPrice,
			pulls == null ? Collections.emptyList() : pulls,
			boosterDisplayName,
			boosterPackId);
	}
}
