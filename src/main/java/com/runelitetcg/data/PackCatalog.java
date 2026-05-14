package com.runelitetcg.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class PackCatalog
{
	private static final Type PACK_LIST_TYPE = new TypeToken<List<BoosterPackDefinition>>() { }.getType();

	private final Gson gson;
	private List<BoosterPackDefinition> boosters = Collections.emptyList();
	private boolean loaded;

	@Inject
	public PackCatalog(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load()
	{
		if (loaded)
		{
			return;
		}

		try (Reader reader = openClasspathReader())
		{
			if (reader == null)
			{
				boosters = Collections.emptyList();
				loaded = true;
				return;
			}
			List<BoosterPackDefinition> parsed = gson.fromJson(reader, PACK_LIST_TYPE);
			boosters = parsed == null ? Collections.emptyList() : Collections.unmodifiableList(parsed);
			loaded = true;
			log.info("Loaded {} booster pack definitions from Packs.json", boosters.size());
		}
		catch (IOException | JsonSyntaxException ex)
		{
			log.warn("Failed reading Packs.json from classpath", ex);
			boosters = Collections.emptyList();
			loaded = true;
		}
	}

	public synchronized List<BoosterPackDefinition> getBoosters()
	{
		return boosters;
	}

	public synchronized void setBoostersForTesting(List<BoosterPackDefinition> testBoosters)
	{
		boosters = testBoosters == null ? Collections.emptyList() : Collections.unmodifiableList(testBoosters);
		loaded = true;
	}

	private Reader openClasspathReader()
	{
		InputStream stream = getClass().getResourceAsStream("/Packs.json");
		if (stream == null)
		{
			log.warn("Packs.json resource missing from plugin classpath");
			return null;
		}
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}
}
