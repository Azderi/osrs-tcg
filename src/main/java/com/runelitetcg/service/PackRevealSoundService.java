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
 * Pack reveal audio: premium-tier ambience ({@code /hum.wav}, looped while any qualifying face-down card remains),
 * matching reveal chime ({@code /reveal.wav}) on flip, per-card deal motion ({@code /card.wav}) when each card begins flying to its slot,
 * {@code /flip.wav} when a face-down card is clicked to flip, and {@code /transfer.wav} when a party card transfer completes.
 * <p>
 * Short samples use a <b>new {@link Clip} per play</b> so several can overlap (flip + premium reveal, stacked deal hits, etc.).
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
	private static final String TRANSFER_SUCCESS_RESOURCE = "/transfer.wav";
	private static final String APEX_PACK_HOVER_RESOURCE = "/apex.wav";
	/** Fade-in for apex pack hover loop (sealed pack, {@link com.runelitetcg.service.PackRevealService.Phase#PACK_READY}). */
	private static final float APEX_HOVER_FADE_IN_SEC = 0.55f;

	private final RuneLiteTcgConfig config;

	private Clip humClip;
	private boolean humOpenFailed;
	private boolean humLoopActive;
	private long lastHumTickNanos = System.nanoTime();
	/** Fade-in envelope 0–1 while premium hum is active ({@code 1} = full level). */
	private float humFade01;

	private boolean revealOpenFailed;
	private boolean flipOpenFailed;
	private boolean cardDealOpenFailed;
	private boolean transferSuccessOpenFailed;

	private Clip apexHoverClip;
	private boolean apexHoverOpenFailed;
	private boolean apexHoverLoopActive;
	private long lastApexHoverTickNanos = System.nanoTime();
	private float apexHoverFade01;

	private final CopyOnWriteArrayList<Clip> activeOneShotClips = new CopyOnWriteArrayList<>();

	/** Greatest card index whose deal-start sound has been played this deal phase ({@code -1} = none). */
	private int dealMotionSoundUpToIndex = -1;

	@Inject
	public PackRevealSoundService(RuneLiteTcgConfig config)
	{
		this.config = config;
	}

	/**
	 * Per-frame premium hum while pack reveal overlay is active.
	 *
	 * @param revealActive           {@link PackRevealService#isActive()}
	 * @param unrevealedMythicActive at least one card that qualifies for hum/reveal is still face-down
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
			log.warn("Could not open hum.wav for pack reveal ambience", ex);
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

	/** One-shot {@code reveal.wav} when a qualifying premium card is flipped (after {@link #playCardFlip()}). */
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

	/** One-shot {@code transfer.wav} when a party card transfer completes (sender and recipient); half default gain. */
	public synchronized void playTransferSuccess()
	{
		if (!config.enableSounds() || transferSuccessOpenFailed)
		{
			return;
		}
		if (!playDisposableOneShot(TRANSFER_SUCCESS_RESOURCE, "transfer.wav", 0.5f))
		{
			transferSuccessOpenFailed = true;
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
		hardStopApexHover();
		hardStopActiveOneShots();
		dealMotionSoundUpToIndex = -1;
	}

	/**
	 * Looped {@code apex.wav} while the sealed apex pack is hovered ({@code wantHoverSound && hoveringPack}).
	 */
	public synchronized void tickApexPackHover(boolean wantHoverSound, boolean hoveringPack)
	{
		if (!wantHoverSound || !config.enableSounds())
		{
			hardStopApexHover();
			return;
		}

		if (apexHoverOpenFailed)
		{
			return;
		}

		if (!hoveringPack)
		{
			hardStopApexHover();
			return;
		}

		try
		{
			ensureApexHoverClipOpen();
		}
		catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex)
		{
			log.warn("Could not open apex.wav for apex pack hover", ex);
			apexHoverOpenFailed = true;
			hardStopApexHover();
			return;
		}

		if (apexHoverClip == null)
		{
			return;
		}

		long now = System.nanoTime();
		float dt = Math.min(0.25f, Math.max(0f, (now - lastApexHoverTickNanos) / 1.0e9f));
		lastApexHoverTickNanos = now;

		if (!apexHoverLoopActive)
		{
			apexHoverClip.stop();
			apexHoverClip.flush();
			apexHoverClip.setFramePosition(0);
			apexHoverFade01 = 0f;
			applyGain(apexHoverClip, 0f);
			apexHoverClip.loop(Clip.LOOP_CONTINUOUSLY);
			apexHoverLoopActive = true;
		}
		else
		{
			apexHoverFade01 = Math.min(1f, apexHoverFade01 + dt / APEX_HOVER_FADE_IN_SEC);
		}
		applyGain(apexHoverClip, apexHoverFade01 * 0.85f);
	}

	private void hardStopApexHover()
	{
		apexHoverLoopActive = false;
		apexHoverFade01 = 0f;
		lastApexHoverTickNanos = System.nanoTime();
		if (apexHoverClip == null)
		{
			return;
		}
		try
		{
			if (apexHoverClip.isRunning())
			{
				apexHoverClip.stop();
			}
			apexHoverClip.flush();
			apexHoverClip.setFramePosition(0);
			applyGain(apexHoverClip, 0f);
		}
		catch (Exception ignored)
		{
			// best-effort
		}
	}

	private void ensureApexHoverClipOpen() throws IOException, UnsupportedAudioFileException, LineUnavailableException
	{
		if (apexHoverClip != null)
		{
			return;
		}
		URL url = PackRevealSoundService.class.getResource(APEX_PACK_HOVER_RESOURCE);
		if (url == null)
		{
			log.warn("Missing resource {}", APEX_PACK_HOVER_RESOURCE);
			apexHoverOpenFailed = true;
			return;
		}
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(url))
		{
			apexHoverClip = AudioSystem.getClip();
			apexHoverClip.open(ais);
		}
		if (!apexHoverClip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			log.warn("apex.wav has no MASTER_GAIN control; apex pack hover sound disabled.");
			apexHoverOpenFailed = true;
			if (apexHoverClip.isOpen())
			{
				apexHoverClip.close();
			}
			apexHoverClip = null;
		}
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
		return playDisposableOneShot(resourcePath, logName, 1f);
	}

	private boolean playDisposableOneShot(String resourcePath, String logName, float linearGain01)
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
			applyGain(clip, linearGain01);
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
			log.warn("hum.wav has no MASTER_GAIN control; pack reveal hum disabled.");
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
