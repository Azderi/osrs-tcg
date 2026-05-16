package com.runelitetcg.service;

import com.runelitetcg.model.TcgState;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

public class CreditAwardServiceTest
{
	@Test
	public void levelUpRewardAnchorsShouldMatchDesign()
		throws Exception
	{
		CreditAwardService service = new CreditAwardService(null, new TcgStateService(TcgState.empty()));

		Method method = CreditAwardService.class.getDeclaredMethod("levelUpReward", int.class);
		method.setAccessible(true);

		Assert.assertEquals(100, ((Integer) method.invoke(service, 1)).intValue());
		Assert.assertEquals(25000, ((Integer) method.invoke(service, 99)).intValue());
	}
}
