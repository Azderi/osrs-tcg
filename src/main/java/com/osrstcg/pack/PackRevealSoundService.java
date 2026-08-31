package com.osrstcg.pack;

import com.osrstcg.OsrsTcgConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

@Slf4j
@Singleton
public class PackRevealSoundService
{
	private static final String HUM_RESOURCE = "/com/osrstcg/sounds/hum.wav";

	private static final String REVEAL_RESOURCE = "/com/osrstcg/sounds/reveal.wav";
	private static final String CARD_DEAL_RESOURCE = "/com/osrstcg/sounds/card.wav";
	private static final String FLIP_RESOURCE = "/com/osrstcg/sounds/flip.wav";
	private static final String APEX_PACK_HOVER_RESOURCE = "/com/osrstcg/sounds/apex.wav";

	private static final float GAIN_APEX_HOVER_DB = linearGainToDb(0.425f);

	private final OsrsTcgConfig config;
	private final AudioPlayer audioPlayer;

	private boolean humOpenFailed;
	private boolean humPlayedThisReveal;

	private boolean revealOpenFailed;
	private boolean flipOpenFailed;
	private boolean cardDealOpenFailed;
	private boolean apexHoverOpenFailed;

	/** Greatest card index whose deal-start sound has been played this deal phase ({@code -1} = none). */
	private int dealMotionSoundUpToIndex = -1;

	@Inject
	public PackRevealSoundService(OsrsTcgConfig config, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.audioPlayer = audioPlayer;
	}

	public synchronized void tryPlayMythicHum(boolean humWanted)
	{
		if (!config.enableSounds() || humOpenFailed || humPlayedThisReveal || !humWanted)
		{
			return;
		}

		if (!playResource(HUM_RESOURCE, "hum.wav", 0f))
		{
			humOpenFailed = true;
			return;
		}

		humPlayedThisReveal = true;
	}

	public synchronized void playMythicReveal()
	{
		if (!config.enableSounds() || revealOpenFailed)
		{
			return;
		}
		if (!playResource(REVEAL_RESOURCE, "reveal.wav", 0f))
		{
			revealOpenFailed = true;
		}
	}

	public synchronized void playCardFlip()
	{
		if (!config.enableSounds() || flipOpenFailed)
		{
			return;
		}
		if (!playResource(FLIP_RESOURCE, "flip.wav", 0f))
		{
			flipOpenFailed = true;
		}
	}

	public synchronized void playApexPackHoverOneShot()
	{
		if (!config.enableSounds() || apexHoverOpenFailed)
		{
			return;
		}
		if (!playResource(APEX_PACK_HOVER_RESOURCE, "apex.wav", GAIN_APEX_HOVER_DB))
		{
			apexHoverOpenFailed = true;
		}
	}

	public synchronized void tickDealMotionSounds(boolean dealPhaseActive, long elapsedMs, int cardCount, long staggerMs)
	{
		if (!dealPhaseActive || !config.enableSounds())
		{
			dealMotionSoundUpToIndex = -1;
			return;
		}

		if (cardCount <= 0 || staggerMs <= 0L || cardDealOpenFailed)
		{
			return;
		}

		while (dealMotionSoundUpToIndex + 1 < cardCount)
		{
			int next = dealMotionSoundUpToIndex + 1;
			if (elapsedMs < next * staggerMs)
			{
				break;
			}
			if (!playResource(CARD_DEAL_RESOURCE, "card.wav", 0f))
			{
				cardDealOpenFailed = true;
				break;
			}
			dealMotionSoundUpToIndex = next;
		}
	}

	public synchronized void resetDealMotionSounds()
	{
		dealMotionSoundUpToIndex = -1;
	}

	public synchronized void hardStop()
	{
		humPlayedThisReveal = false;
		dealMotionSoundUpToIndex = -1;
	}

	private boolean playResource(String resourcePath, String logName, float gainDb)
	{
		if (PackRevealSoundService.class.getResource(resourcePath) == null)
		{
			log.warn("Missing resource {}", resourcePath);
			return false;
		}

		try
		{
			audioPlayer.play(PackRevealSoundService.class, resourcePath, gainDb);
			return true;
		}
		catch (Exception ex)
		{
			log.warn("Could not play {} ({})", logName, resourcePath, ex);
			return false;
		}
	}

	private static float linearGainToDb(float linear01)
	{
		float v = Math.max(0f, Math.min(1f, linear01));
		if (v < 0.0005f)
		{
			return -80f;
		}
		return (float) (20.0 * Math.log10(v));
	}
}
