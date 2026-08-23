package com.osrstcg.ui.welcome;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads welcome-tab paragraphs from bundled {@code /com/osrstcg/welcome/Welcome.json}.
 */
@Singleton
@Slf4j
public class WelcomeContent
{
	private static final Type PARAGRAPH_LIST_TYPE = new TypeToken<List<WelcomeParagraph>>() { }.getType();
	private static final Color DEFAULT_COLOR = new Color(0xBBBBBB);

	private final Gson gson;
	private List<WelcomeParagraph> paragraphs = Collections.emptyList();
	private boolean loaded;

	@Inject
	public WelcomeContent(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load()
	{
		if (loaded)
		{
			return;
		}
		loaded = true;
		try (Reader reader = openClasspathReader("/com/osrstcg/welcome/Welcome.json"))
		{
			if (reader == null)
			{
				log.warn("Welcome.json resource missing from plugin classpath");
				paragraphs = Collections.emptyList();
				return;
			}
			List<WelcomeParagraph> parsed = gson.fromJson(reader, PARAGRAPH_LIST_TYPE);
			if (parsed == null || parsed.isEmpty())
			{
				paragraphs = Collections.emptyList();
				return;
			}
			List<WelcomeParagraph> clean = new ArrayList<>(parsed.size());
			for (WelcomeParagraph p : parsed)
			{
				if (p == null)
				{
					continue;
				}
				String text = p.getText();
				if (text == null || text.isBlank())
				{
					continue;
				}
				clean.add(p);
			}
			paragraphs = Collections.unmodifiableList(clean);
			log.info("Loaded {} welcome paragraphs from Welcome.json", paragraphs.size());
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading Welcome.json from classpath", ex);
			paragraphs = Collections.emptyList();
		}
	}

	public synchronized List<WelcomeParagraph> getParagraphs()
	{
		load();
		return paragraphs;
	}

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

	public static boolean isBold(Boolean bold)
	{
		return Boolean.TRUE.equals(bold);
	}

	/** @return point size to apply, or {@code <= 0} to keep the base font size */
	public static int resolveFontSize(Integer size)
	{
		return size == null ? 0 : size;
	}

	private Reader openClasspathReader(String resourcePath)
	{
		InputStream stream = getClass().getResourceAsStream(resourcePath);
		if (stream == null)
		{
			return null;
		}
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}
}
