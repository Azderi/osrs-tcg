package com.osrstcg.state;

import java.util.List;

/**
 * One card's grouped owned copies, keyed by name, for profile save and web share JSON. Built and
 * expanded via {@link CardEntrySerializer}.
 */
public final class CardEntry
{
	public String cardName;
	/** Owned copies of this card (each entry is one physical/foil variant instance or legacy quantity group). */
	public List<CardVariant> variants;
}
