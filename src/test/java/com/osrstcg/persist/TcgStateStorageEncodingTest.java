package com.osrstcg.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TcgStateStorageEncodingTest
{
	private static final String SAMPLE_JSON = "{\"schemaVersion\":6,\"credits\":100}";

	@Test
	public void encodeRoundTripV3()
	{
		String encoded = TcgStateStorageEncoding.encode(SAMPLE_JSON);
		assertTrue(encoded.startsWith(TcgStateStorageEncoding.STORAGE_PREFIX_V3));
		assertEquals(SAMPLE_JSON, TcgStateStorageEncoding.decode(encoded));
	}

	@Test
	public void decodeEmptyReturnsEmpty()
	{
		assertEquals("", TcgStateStorageEncoding.decode(""));
	}
}
