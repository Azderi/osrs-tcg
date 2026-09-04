package com.osrstcg.ui.welcome;
/**
 * One welcome-tab paragraph.
 */
public final class WelcomeParagraph
{
	private final String text;
	private final String color;
	private final Integer size;
	private final Boolean bold;
/**
	 * @param color raw color string, resolved via {@link WelcomeContent#resolveColor(String)}
	 * @param size point size, or {@code null} to keep the base font size (see {@link WelcomeContent#resolveFontSize(Integer)})
	 * @param bold whether the paragraph renders bold; {@code null} treated as false (see {@link WelcomeContent#isBold(Boolean)})
	 */
	public WelcomeParagraph(String text, String color, Integer size, Boolean bold)
	{
		this.text = text;
		this.color = color;
		this.size = size;
		this.bold = bold;
	}
/** @return the paragraph's raw text. */
	public String getText()
	{
		return text;
	}
/** @return the paragraph's raw (unresolved) color string. */
	public String getColor()
	{
		return color;
	}
/** @return the paragraph's raw (unresolved) font size. */
	public Integer getSize()
	{
		return size;
	}
/** @return the paragraph's raw (unresolved) bold flag. */
	public Boolean getBold()
	{
		return bold;
	}
}
