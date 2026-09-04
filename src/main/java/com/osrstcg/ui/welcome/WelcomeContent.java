package com.osrstcg.ui.welcome;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
/**
 * Hardcoded welcome-tab paragraphs (previously loaded from Welcome.json).
 */
@Singleton
public class WelcomeContent
{
	private static final Color DEFAULT_COLOR = new Color(0xBBBBBB);

	private static final List<WelcomeParagraph> PARAGRAPHS = List.of(
		new WelcomeParagraph("Welcome to OSRS TCG", "#e8c458", 20, true),
		new WelcomeParagraph(
			"Play OSRS to earn credits, buy booster packs, collect cards, and trade with other players.",
			"#FFFFFF", 16, false),
		new WelcomeParagraph(
			"Card scores are not meant to accurately reflect the in-gamevalue, usefulness of the items or difficulty of the monster and boss encounters.",
			"#BBBBBB", 16, false),
		new WelcomeParagraph(
			"One copy of each card you owned before migrating is imported and kept as a beta card. Beta cards cannot be traded or sold and do not count towards your collection stats. Cards from packs after migrating are normal and can be traded.",
			"#FFFF00", 16, false),
		new WelcomeParagraph("Disclaimer", "#e8c458", 20, true),
		new WelcomeParagraph(
			"OSRS TCG is a fan-made minigame for fun. Cards have no real-world or in-game value.\n\nDo not buy or sell cards for money, bonds, gold, or items. Trade at your own risk.",
			"#BBBBBB", 16, false)
	);

	@Inject
	public WelcomeContent()
	{
	}
/** @return the fixed list of welcome-tab paragraphs, in display order. */
	public List<WelcomeParagraph> getParagraphs()
	{
		return PARAGRAPHS;
	}
/**
	 * Resolves a paragraph's color string: a handful of named colors, otherwise a hex string
	 * (with or without a leading {@code #}), falling back to {@link #DEFAULT_COLOR} if blank or unparsable.
	 */
	public static Color resolveColor(String raw)
	{
		if (raw == null || raw.isBlank())
		{
			return DEFAULT_COLOR;
		}
		String t = raw.trim();
		String lower = t.toLowerCase(Locale.ROOT);
		switch (lower)
		{
			case "white":
				return Color.WHITE;
			case "yellow":
				return Color.YELLOW;
			case "red":
				return Color.RED;
			case "black":
				return Color.BLACK;
			default:
				break;
		}
		try
		{
			if (t.charAt(0) != '#' && !lower.startsWith("0x"))
			{
				t = "#" + t;
			}
			return Color.decode(t);
		}
		catch (NumberFormatException ex)
		{
			return DEFAULT_COLOR;
		}
	}
/** @return true only if {@code bold} is non-null and {@code true}. */
	public static boolean isBold(Boolean bold)
	{
		return Boolean.TRUE.equals(bold);
	}
/** @return point size to apply, or {@code <= 0} to keep the base font size */
	public static int resolveFontSize(Integer size)
	{
		return size == null ? 0 : size;
	}
}
