package com.osrstcg.ui.welcome;

import lombok.Data;

/**
 * One welcome-tab paragraph loaded from {@code Welcome.json}.
 */
@Data
public class WelcomeParagraph
{
	/** Paragraph body. */
	private String text;
	/** Hex color ({@code #RRGGBB}) or a simple color name. */
	private String color;
	/**
	 * Font tier: {@code >= 16} → regular; smaller → small. Ignored when {@link #bold} is true.
	 */
	private Integer size;
	/** When true, uses the RuneScape bold font face. */
	private Boolean bold;
}
