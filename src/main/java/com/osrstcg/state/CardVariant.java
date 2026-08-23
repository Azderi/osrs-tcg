package com.osrstcg.state;

/**
 * One owned copy of a card within a {@link CardEntry} group (profile save and web share schema).
 */
public final class CardVariant
{
	/** Stable instance id when present (cloud sync / newer profile saves). */
	public String id;
	/** Omitted when false; absent or null means normal. */
	public Boolean foil;
	public String pulledBy;
	public Long pulledAt;
	/** Profile save only; omitted when false. Not sent on web share. */
	public Boolean locked;
	/** Legacy profile save: expanded on load when present. */
	public Integer quantity;
	/** Legacy profile save: expanded on load when present. */
	public Integer lockedQuantity;
	/** Wear 0.01–100; omitted when null. */
	public Double condition;
	/** Migrated beta copy; omitted when false. */
	public Boolean beta;
	/** Origin label. */
	public String source;
}
