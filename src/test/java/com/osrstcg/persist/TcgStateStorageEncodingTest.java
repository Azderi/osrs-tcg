package com.osrstcg.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
	public void encodeLegacyV2UsesXorPrefix()
	{
		String legacy = TcgStateStorageEncoding.encodeLegacyV2(SAMPLE_JSON);
		assertTrue(legacy.startsWith(TcgStateStorageEncoding.STORAGE_PREFIX_V2));
		assertEquals(SAMPLE_JSON, TcgStateStorageEncoding.decode(legacy));
	}

	@Test
	public void v3DiffersFromV2ForSameJson()
	{
		String v3 = TcgStateStorageEncoding.encode(SAMPLE_JSON);
		String v2 = TcgStateStorageEncoding.encodeLegacyV2(SAMPLE_JSON);
		assertNotEquals(v3, v2);
	}

	@Test
	public void decodeEmptyReturnsEmpty()
	{
		assertEquals("", TcgStateStorageEncoding.decode(""));
	}
}
