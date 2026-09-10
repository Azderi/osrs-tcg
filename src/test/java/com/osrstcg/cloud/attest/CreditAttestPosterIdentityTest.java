package com.osrstcg.cloud.attest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;

public class CreditAttestPosterIdentityTest
{
	@Test
	public void assertPostIdentityAllowsTeardownWhenLiveUnset() throws Exception
	{
		CreditAttestPoster.assertPostIdentity(42L, -1L, true);
	}

	@Test
	public void assertPostIdentityRejectsLiveAccountSwitch()
	{
		try
		{
			CreditAttestPoster.assertPostIdentity(42L, 99L, true);
			fail("expected IOException");
		}
		catch (IOException ex)
		{
			assertEquals("Account changed during credit attest flush", ex.getMessage());
		}
	}

	@Test
	public void assertPostIdentityRejectsUnboundTokens()
	{
		try
		{
			CreditAttestPoster.assertPostIdentity(42L, -1L, false);
			fail("expected IOException");
		}
		catch (IOException ex)
		{
			assertEquals("Tokens not bound to credit attest account", ex.getMessage());
		}
	}

	@Test
	public void assertPostIdentityRejectsMissingBoundHash()
	{
		try
		{
			CreditAttestPoster.assertPostIdentity(-1L, -1L, true);
			fail("expected IOException");
		}
		catch (IOException ex)
		{
			assertEquals("Missing account hash for credit attest flush", ex.getMessage());
		}
	}
}
