package com.osrstcg.data;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deserializes {@code "category"} from either a JSON string or array of strings into a {@link List}.
 */
public final class CategoryListTypeAdapter extends TypeAdapter<List<String>>
{
	@Override
	public void write(JsonWriter out, List<String> value) throws IOException
	{
		if (value == null)
		{
			out.nullValue();
			return;
		}
		out.beginArray();
		for (String s : value)
		{
			out.value(s);
		}
		out.endArray();
	}

	@Override
	public List<String> read(JsonReader in) throws IOException
	{
		JsonToken peek = in.peek();
		if (peek == JsonToken.NULL)
		{
			in.nextNull();
			return Collections.emptyList();
		}
		if (peek == JsonToken.STRING)
		{
			return Collections.singletonList(in.nextString());
		}
		if (peek == JsonToken.BEGIN_ARRAY)
		{
			List<String> out = new ArrayList<>();
			in.beginArray();
			while (in.hasNext())
			{
				if (in.peek() == JsonToken.STRING)
				{
					out.add(in.nextString());
				}
				else
				{
					in.skipValue();
				}
			}
			in.endArray();
			return out;
		}
		in.skipValue();
		return Collections.emptyList();
	}
}
