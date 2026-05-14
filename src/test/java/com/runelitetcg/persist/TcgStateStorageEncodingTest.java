package com.runelitetcg.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TcgStateStorageEncodingTest
{
	@Test
	public void roundTripLargeJsonUsesPrefix()
	{
		StringBuilder sb = new StringBuilder("{\"collection\":{");
		for (int i = 0; i < 200; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append("\"Card ").append(i).append("|0\":1");
		}
		sb.append("}}");
		String plain = sb.toString();
		String stored = TcgStateStorageEncoding.encode(plain);
		assertTrue(stored.startsWith(TcgStateStorageEncoding.GZIP_V1_PREFIX));
		assertTrue(stored.length() < plain.length());
		assertEquals(plain, TcgStateStorageEncoding.decode(stored));
	}

	@Test
	public void smallJsonRoundTripCompressed()
	{
		String plain = "{}";
		String stored = TcgStateStorageEncoding.encode(plain);
		assertTrue(stored.startsWith(TcgStateStorageEncoding.GZIP_V1_PREFIX));
		assertEquals(plain, TcgStateStorageEncoding.decode(stored));
	}

	@Test
	public void decodeNonPrefixedReturnsEmpty()
	{
		assertEquals("", TcgStateStorageEncoding.decode("{\"credits\":1}"));
	}
}
