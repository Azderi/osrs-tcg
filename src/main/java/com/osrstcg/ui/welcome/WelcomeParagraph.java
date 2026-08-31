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

	public WelcomeParagraph(String text, String color, Integer size, Boolean bold)
	{
		this.text = text;
		this.color = color;
		this.size = size;
		this.bold = bold;
	}

	public String getText()
	{
		return text;
	}

	public String getColor()
	{
		return color;
	}

	public Integer getSize()
	{
		return size;
	}

	public Boolean getBold()
	{
		return bold;
	}
}
