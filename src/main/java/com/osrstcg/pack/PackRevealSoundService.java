package com.osrstcg.pack;

import com.osrstcg.OsrsTcgConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays the pack-reveal sound effects (mythic hum/reveal, card flip, card deal stagger, apex hover),
 * gated by {@link OsrsTcgConfig#enableSounds()}. Not thread-confined to the client thread, but all
 * public methods are {@code synchronized} against shared per-resource/per-reveal state.
 */
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

	/** Wires the config (for the sound-enabled toggle) and the shared audio player. */
	@Inject
	public PackRevealSoundService(OsrsTcgConfig config, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.audioPlayer = audioPlayer;
	}

	/** Plays the mythic hum once per reveal, when wanted and not previously failed/played. */
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

	/** Plays the mythic/legendary-foil reveal sting. */
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

	/** Plays the card-flip sound. */
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

	/** Plays the apex-pack hover sound once, at reduced gain; no-ops after it fails to open. */
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

	/**
	 * Timer-driven: called every paint frame during the card-deal phase to play one deal-stagger sound
	 * per card whose stagger offset ({@code index * staggerMs}) has elapsed since the phase began.
	 * Resets the played-up-to index when the deal phase isn't active.
	 */
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

	/** Resets the deal-stagger progress so the next batch's deal sounds play from the start. */
	public synchronized void resetDealMotionSounds()
	{
		dealMotionSoundUpToIndex = -1;
	}

	/** Clears per-reveal sound state (mythic hum played flag, deal-stagger progress) when a reveal ends or aborts. */
	public synchronized void hardStop()
	{
		humPlayedThisReveal = false;
		dealMotionSoundUpToIndex = -1;
	}

	/**
	 * Plays the given resource at {@code gainDb}, logging and returning {@code false} if the resource is
	 * missing or playback throws.
	 */
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

	/** Converts a 0..1 linear gain to decibels, clamping input to {@code [0, 1]} and flooring silence at -80dB. */
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
