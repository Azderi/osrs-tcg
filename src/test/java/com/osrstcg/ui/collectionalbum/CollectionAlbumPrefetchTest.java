package com.osrstcg.ui.collectionalbum;

import org.junit.Assert;
import org.junit.Test;

/** Pins the page chosen for background image prefetch after a page-turn. */
public class CollectionAlbumPrefetchTest
{
	@Test
	public void prefetchesNextPageWhenBrowsingForward()
	{
		Assert.assertEquals(3, CollectionAlbumWindow.adjacentPrefetchPage(2, 10, 1));
	}

	@Test
	public void prefetchesPreviousPageWhenBrowsingBackward()
	{
		Assert.assertEquals(1, CollectionAlbumWindow.adjacentPrefetchPage(2, 10, -1));
	}

	@Test
	public void fallsBackToPreviousPageOnLastPage()
	{
		Assert.assertEquals(8, CollectionAlbumWindow.adjacentPrefetchPage(9, 10, 1));
	}

	@Test
	public void fallsBackToNextPageOnFirstPage()
	{
		Assert.assertEquals(1, CollectionAlbumWindow.adjacentPrefetchPage(0, 10, -1));
	}

	@Test
	public void returnsMinusOneWhenNoOtherPageExists()
	{
		Assert.assertEquals(-1, CollectionAlbumWindow.adjacentPrefetchPage(0, 1, 1));
		Assert.assertEquals(-1, CollectionAlbumWindow.adjacentPrefetchPage(0, 0, -1));
	}
}
