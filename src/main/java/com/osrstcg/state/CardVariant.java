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
	/** Legacy profile save: expanded on load when present. */
	public Integer quantity;
	/** Migrated beta copy; omitted when false. */
	public Boolean beta;
}
