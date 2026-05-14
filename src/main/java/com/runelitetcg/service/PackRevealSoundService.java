package com.runelitetcg.service;

import com.runelitetcg.RuneLiteTcgConfig;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.extern.slf4j.Slf4j;

/**
 * Pack reveal audio: Godly-tier ambience ({@code /hum.wav}, looped while any face-down Godly-tier card remains),
 * Godly reveal chime ({@code /reveal.wav}), per-card deal motion ({@code /card.wav}) when each card begins flying to its slot,
 * and {@code /flip.wav} when a face-down card is clicked to flip.
 * <p>
 * Short samples use a <b>new {@link Clip} per play</b> so several can overlap (flip + Godly reveal, stacked deal hits, etc.).
 * The hum stays on one looped clip with a short fade-in when it starts.
 */
@Slf4j
@Singleton
public class PackRevealSoundService
{
	private static final String HUM_RESOURCE = "/hum.wav";
	/** Seconds from silence to full hum level after the loop starts. */
	private static final float HUM_FADE_IN_SEC = 0.9f;

	private static final String REVEAL_RESOURCE = "/reveal.wav";
	private static final String CARD_DEAL_RESOURCE = "/card.wav";
	private static final String FLIP_RESOURCE = "/flip.wav";

	private final RuneLiteTcgConfig config;

	private Clip humClip;
	private boolean humOpenFailed;
	private boolean humLoopActive;
	private long lastHumTickNanos = System.nanoTime();
	/** Fade-in envelope 0–1 while Godly hum is active ({@code 1} = full level). */
	private float humFade01;

	private boolean revealOpenFailed;
	private boolean flipOpenFailed;
	private boolean cardDealOpenFailed;

	private final CopyOnWriteArrayList<Clip> activeOneShotClips = new CopyOnWriteArrayList<>();

	/** Greatest card index whose deal-start sound has been played this deal phase ({@code -1} = none). */
	private int dealMotionSoundUpToIndex = -1;

	@Inject
	public PackRevealSoundService(RuneLiteTcgConfig config)
	{
		this.config = config;
	}

	/**
	 * Per-frame Godly hum while pack reveal overlay is active.
	 *
	 * @param revealActive           {@link PackRevealService#isActive()}
	 * @param unrevealedMythicActive at least one Godly-tier card is still face-down in the opening flow
	 */
	public synchronized void tickMythicHum(boolean revealActive, boolean unrevealedMythicActive)
	{
		if (!revealActive || !config.enableSounds())
		{
			hardStopHum();
			return;
		}

		if (humOpenFailed)
		{
			return;
		}

		if (!unrevealedMythicActive)
		{
			hardStopHum();
			return;
		}

		try
		{
			ensureHumClipOpen();
		}
		catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex)
		{
			log.warn("Could not open hum.wav for Godly pack ambience", ex);
			humOpenFailed = true;
			hardStopHum();
			return;
		}

		if (humClip == null)
		{
			return;
		}

		long now = System.nanoTime();
		float dt = Math.min(0.25f, Math.max(0f, (now - lastHumTickNanos) / 1.0e9f));
		lastHumTickNanos = now;

		if (!humLoopActive)
		{
			humClip.stop();
			humClip.flush();
			humClip.setFramePosition(0);
			humFade01 = 0f;
			applyGain(humClip, 0f);
			humClip.loop(Clip.LOOP_CONTINUOUSLY);
			humLoopActive = true;
		}
		else
		{
			humFade01 = Math.min(1f, humFade01 + dt / HUM_FADE_IN_SEC);
		}
		applyGain(humClip, humFade01);
	}

	/** One-shot {@code reveal.wav} when a face-down Godly-tier card is flipped (after {@link #playCardFlip()}). */
	public synchronized void playMythicReveal()
	{
		if (!config.enableSounds() || revealOpenFailed)
		{
			return;
		}
		if (!playDisposableOneShot(REVEAL_RESOURCE, "reveal.wav"))
		{
			revealOpenFailed = true;
		}
	}

	/** One-shot {@code flip.wav} when any face-down card is clicked to flip during click-to-reveal. */
	public synchronized void playCardFlip()
	{
		if (!config.enableSounds() || flipOpenFailed)
		{
			return;
		}
		if (!playDisposableOneShot(FLIP_RESOURCE, "flip.wav"))
		{
			flipOpenFailed = true;
		}
	}

	/**
	 * While {@code dealPhaseActive}, plays {@code /card.wav} once per card when its deal flight starts
	 * (same timing as {@link com.runelitetcg.overlay.PackRevealOverlay}: {@code elapsed >= index * staggerMs}).
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
			if (!playDisposableOneShot(CARD_DEAL_RESOURCE, "card.wav"))
			{
				cardDealOpenFailed = true;
				break;
			}
			dealMotionSoundUpToIndex = next;
		}
	}

	/** Stops hum and silences any in-flight one-shot clips (overlay closed or plugin shut down). */
	public synchronized void hardStop()
	{
		hardStopHum();
		hardStopActiveOneShots();
		dealMotionSoundUpToIndex = -1;
	}

	private void hardStopHum()
	{
		humLoopActive = false;
		humFade01 = 0f;
		lastHumTickNanos = System.nanoTime();
		if (humClip == null)
		{
			return;
		}
		try
		{
			if (humClip.isRunning())
			{
				humClip.stop();
			}
			humClip.flush();
			humClip.setFramePosition(0);
			applyGain(humClip, 0f);
		}
		catch (Exception ignored)
		{
			// best-effort
		}
	}

	private void hardStopActiveOneShots()
	{
		ArrayList<Clip> snapshot = new ArrayList<>(activeOneShotClips);
		activeOneShotClips.clear();
		for (Clip c : snapshot)
		{
			try
			{
				if (c.isOpen())
				{
					if (c.isRunning())
					{
						c.stop();
					}
					c.flush();
					c.close();
				}
			}
			catch (Exception ignored)
			{
				// best-effort
			}
		}
	}

	/**
	 * Opens a fresh {@link Clip}, plays it once, and disposes it on {@link LineEvent.Type#STOP}.
	 *
	 * @return {@code false} if the resource is missing or could not be opened/started
	 */
	private boolean playDisposableOneShot(String resourcePath, String logName)
	{
		URL url = PackRevealSoundService.class.getResource(resourcePath);
		if (url == null)
		{
			log.warn("Missing resource {}", resourcePath);
			return false;
		}
		Clip clip;
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(url))
		{
			clip = AudioSystem.getClip();
			clip.open(ais);
		}
		catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex)
		{
			log.warn("Could not open {} ({})", logName, resourcePath, ex);
			return false;
		}
		try
		{
			applyGain(clip, 1f);
			Clip c = clip;
			c.addLineListener(new LineListener()
			{
				@Override
				public void update(LineEvent ev)
				{
					if (ev.getType() != LineEvent.Type.STOP)
					{
						return;
					}
					try
					{
						ev.getLine().close();
					}
					catch (Exception ignored)
					{
						// best-effort
					}
					activeOneShotClips.remove(c);
				}
			});
			activeOneShotClips.add(c);
			c.start();
			return true;
		}
		catch (Exception ex)
		{
			log.debug("{} playback failed", logName, ex);
			activeOneShotClips.remove(clip);
			try
			{
				clip.close();
			}
			catch (Exception ignored)
			{
				// best-effort
			}
			return false;
		}
	}

	private void ensureHumClipOpen() throws IOException, UnsupportedAudioFileException, LineUnavailableException
	{
		if (humClip != null)
		{
			return;
		}
		URL url = PackRevealSoundService.class.getResource(HUM_RESOURCE);
		if (url == null)
		{
			log.warn("Missing resource {}", HUM_RESOURCE);
			humOpenFailed = true;
			return;
		}
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(url))
		{
			humClip = AudioSystem.getClip();
			humClip.open(ais);
		}
		if (!humClip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			log.warn("hum.wav has no MASTER_GAIN control; Godly pack hum disabled.");
			humOpenFailed = true;
			if (humClip.isOpen())
			{
				humClip.close();
			}
			humClip = null;
		}
	}

	private static void applyGain(Clip clip, float linear01)
	{
		if (clip == null || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl fc = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		float v = Math.max(0f, Math.min(1f, linear01));
		if (v < 0.0005f)
		{
			fc.setValue(fc.getMinimum());
			return;
		}
		float dB = (float) (20.0 * Math.log10(v));
		dB = Math.max(fc.getMinimum(), Math.min(fc.getMaximum(), dB));
		fc.setValue(dB);
	}
}
