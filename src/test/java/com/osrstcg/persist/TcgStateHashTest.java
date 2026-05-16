package com.osrstcg.persist;

import java.util.Locale;
import org.junit.Assert;
import org.junit.Test;

public class TcgStateHashTest
{
	@Test
	public void emptyStringSha256MatchesKnownVector()
	{
		Assert.assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			TcgStateHash.hexOfUtf8(""));
	}

	@Test
	public void hexIsLowercaseAndStable()
	{
		String h = TcgStateHash.hexOfUtf8("RLTCG_v1:test");
		Assert.assertEquals(64, h.length());
		Assert.assertEquals(h, h.toLowerCase(Locale.ROOT));
	}
}
