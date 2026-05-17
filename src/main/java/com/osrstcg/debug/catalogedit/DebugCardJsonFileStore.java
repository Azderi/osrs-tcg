package com.osrstcg.debug.catalogedit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
final class DebugCardJsonFileStore
{
	private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
	private final DebugCardJsonPaths paths;

	@Inject
	DebugCardJsonFileStore(DebugCardJsonPaths paths)
	{
		this.paths = paths;
	}

	Optional<Path> workspaceCardJson()
	{
		return paths.resolveWorkspaceCardJson();
	}

	JsonArray readArray(Path file) throws IOException
	{
		String raw = Files.readString(file, StandardCharsets.UTF_8);
		JsonElement root = new JsonParser().parse(raw);
		if (!root.isJsonArray())
		{
			throw new IOException("Card.json root must be a JSON array");
		}
		return root.getAsJsonArray();
	}

	Optional<JsonObject> findByName(JsonArray array, String cardName)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return Optional.empty();
		}
		String target = cardName.trim();
		for (JsonElement el : array)
		{
			if (!el.isJsonObject())
			{
				continue;
			}
			JsonObject obj = el.getAsJsonObject();
			String name = stringField(obj, "name");
			if (name != null && name.equals(target))
			{
				return Optional.of(obj);
			}
		}
		return Optional.empty();
	}

	int indexByName(JsonArray array, String cardName)
	{
		if (cardName == null || cardName.trim().isEmpty())
		{
			return -1;
		}
		String target = cardName.trim();
		for (int i = 0; i < array.size(); i++)
		{
			JsonElement el = array.get(i);
			if (!el.isJsonObject())
			{
				continue;
			}
			String name = stringField(el.getAsJsonObject(), "name");
			if (name != null && name.equals(target))
			{
				return i;
			}
		}
		return -1;
	}

	void writeArray(Path file, JsonArray array) throws IOException
	{
		Path parent = file.getParent();
		if (parent != null)
		{
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		String json = prettyGson.toJson(array);
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		try
		{
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex)
		{
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	void updateCard(Path file, String originalName, CardJsonEdit edit) throws IOException
	{
		JsonArray array = readArray(file);
		int idx = indexByName(array, originalName);
		if (idx < 0)
		{
			throw new IOException("Card not found in Card.json: " + originalName);
		}
		JsonObject existing = array.get(idx).getAsJsonObject().deepCopy();
		applyEdit(existing, edit);
		array.set(idx, existing);
		writeArray(file, array);
	}

	void deleteCard(Path file, String cardName) throws IOException
	{
		JsonArray array = readArray(file);
		int idx = indexByName(array, cardName);
		if (idx < 0)
		{
			throw new IOException("Card not found in Card.json: " + cardName);
		}
		array.remove(idx);
		writeArray(file, array);
	}

	static String stringField(JsonObject obj, String key)
	{
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return null;
		}
		JsonElement el = obj.get(key);
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
		{
			return el.getAsString();
		}
		return null;
	}

	static List<String> categoryField(JsonObject obj)
	{
		List<String> out = new ArrayList<>();
		if (obj == null || !obj.has("category") || obj.get("category").isJsonNull())
		{
			return out;
		}
		JsonElement cat = obj.get("category");
		if (cat.isJsonPrimitive() && cat.getAsJsonPrimitive().isString())
		{
			out.add(cat.getAsString());
			return out;
		}
		if (cat.isJsonArray())
		{
			for (JsonElement el : cat.getAsJsonArray())
			{
				if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
				{
					String s = el.getAsString();
					if (s != null && !s.trim().isEmpty())
					{
						out.add(s.trim());
					}
				}
			}
		}
		return out;
	}

	static Long longField(JsonObject obj, String key)
	{
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return null;
		}
		JsonElement el = obj.get(key);
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber())
		{
			return el.getAsLong();
		}
		return null;
	}

	static Integer intField(JsonObject obj, String key)
	{
		Long v = longField(obj, key);
		return v == null ? null : v.intValue();
	}

	static Boolean boolField(JsonObject obj, String key)
	{
		if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull())
		{
			return null;
		}
		JsonElement el = obj.get(key);
		if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean())
		{
			return el.getAsBoolean();
		}
		return null;
	}

	private static void applyEdit(JsonObject target, CardJsonEdit edit)
	{
		if (edit.getImageUrl() != null)
		{
			target.addProperty("imageUrl", edit.getImageUrl().trim());
		}
		else if (target.has("imageUrl"))
		{
			target.remove("imageUrl");
		}

		if (edit.getExamine() != null)
		{
			target.addProperty("examine", edit.getExamine());
		}
		else if (target.has("examine"))
		{
			target.remove("examine");
		}

		if (edit.getValue() != null)
		{
			target.addProperty("value", edit.getValue());
		}
		else if (target.has("value"))
		{
			target.remove("value");
		}

		if (edit.getLevel() != null)
		{
			target.addProperty("level", edit.getLevel());
		}
		else if (target.has("level"))
		{
			target.remove("level");
		}

		if (edit.getOverrideScore() != null)
		{
			target.addProperty("overrideScore", edit.getOverrideScore());
		}
		else if (target.has("overrideScore"))
		{
			target.remove("overrideScore");
		}

		if (edit.getQuestItem() != null)
		{
			target.addProperty("questItem", edit.getQuestItem());
		}
		else if (target.has("questItem"))
		{
			target.remove("questItem");
		}

		JsonArray cats = new JsonArray();
		for (String c : edit.getCategory())
		{
			if (c != null && !c.trim().isEmpty())
			{
				cats.add(c.trim());
			}
		}
		target.add("category", cats);
	}

	@Value
	static class CardJsonEdit
	{
		List<String> category;
		String imageUrl;
		String examine;
		Long value;
		Integer level;
		Long overrideScore;
		Boolean questItem;
	}

	static String formatCategories(List<String> tags)
	{
		if (tags == null || tags.isEmpty())
		{
			return "";
		}
		return String.join(", ", tags);
	}

	static List<String> parseCategories(String raw)
	{
		List<String> out = new ArrayList<>();
		if (raw == null || raw.trim().isEmpty())
		{
			return out;
		}
		for (String part : raw.split(","))
		{
			if (part != null && !part.trim().isEmpty())
			{
				out.add(part.trim());
			}
		}
		return out;
	}

	static String readErrorMessage(Exception ex)
	{
		if (ex instanceof JsonSyntaxException)
		{
			return "Card.json is not valid JSON: " + ex.getMessage();
		}
		if (ex instanceof IOException)
		{
			return ex.getMessage();
		}
		return ex.getClass().getSimpleName() + ": " + ex.getMessage();
	}
}
