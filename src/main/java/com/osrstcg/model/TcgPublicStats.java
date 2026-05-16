package com.osrstcg.model;

import lombok.Value;

/** Collection stats shown for {@code !tcg} chat command (matches sidebar overview semantics). */
@Value
public class TcgPublicStats
{
	long collectionScore;
	double completionPct;
	int uniqueOwned;
	int totalCardPool;
	long openedPacks;
	int totalCardsOwned;
}
