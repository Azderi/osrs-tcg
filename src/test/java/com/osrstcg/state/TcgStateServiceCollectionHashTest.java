package com.osrstcg.state;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TcgStateServiceCollectionHashTest
{
	@Test
	public void adoptCloudCollectionHashWarmsMemoryOnlyCache()
	{
		TcgStateService service = new TcgStateService(TcgState.empty());
		assertEquals("", service.getCloudCollectionHash());

		service.adoptCloudCollectionHash("  AbC  ");
		assertEquals("AbC", service.getCloudCollectionHash());

		service.adoptCloudCollectionHash("abc");
		assertEquals("AbC", service.getCloudCollectionHash());

		service.adoptCloudCollectionHash("def");
		assertEquals("def", service.getCloudCollectionHash());
	}
}
