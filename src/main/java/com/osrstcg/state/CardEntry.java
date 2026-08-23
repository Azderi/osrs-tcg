package com.osrstcg.state;

import java.util.List;

/**
 * Owned copies of one card name, grouped for compact JSON (profile save and web share schema).
 */
public final class CardEntry
{
	public String cardName;
	public List<CardVariant> variants;
}
