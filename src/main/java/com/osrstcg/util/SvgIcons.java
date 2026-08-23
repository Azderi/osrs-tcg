package com.osrstcg.util;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import lombok.extern.slf4j.Slf4j;

/**
 * Rasterizes classpath SVG resources (title-bar Discord / Patreon icons).
 */
@Slf4j
public final class SvgIcons
{
	private SvgIcons()
	{
	}

	/**
	 * Rasterize an SVG so it fits inside {@code maxWidth}×{@code maxHeight} without changing aspect ratio.
	 */
	public static BufferedImage rasterizeFit(String classpathPath, int maxWidth, int maxHeight)
	{
		if (classpathPath == null || classpathPath.isBlank() || maxWidth <= 0 || maxHeight <= 0)
		{
			return null;
		}
		String path = classpathPath.startsWith("/") ? classpathPath : "/" + classpathPath;
		URL resource = SvgIcons.class.getResource(path);
		if (resource == null)
		{
			log.warn("SVG resource missing: {}", path);
			return null;
		}
		URI xmlBase = null;
		try
		{
			xmlBase = resource.toURI();
		}
		catch (URISyntaxException ex)
		{
			log.debug("SVG resource URI unavailable for {}: {}", path, ex.toString());
		}
		try (InputStream in = resource.openStream())
		{
			SVGDocument document = new SVGLoader().load(in, xmlBase, LoaderContext.createDefault());
			if (document == null)
			{
				log.warn("Failed to parse SVG: {}", path);
				return null;
			}
			var size = document.size();
			float srcW = size != null && size.width > 0f ? size.width : maxWidth;
			float srcH = size != null && size.height > 0f ? size.height : maxHeight;
			double scale = Math.min(maxWidth / (double) srcW, maxHeight / (double) srcH);
			int width = Math.max(1, (int) Math.round(srcW * scale));
			int height = Math.max(1, (int) Math.round(srcH * scale));

			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.scale(scale, scale);
				document.render(null, g);
			}
			finally
			{
				g.dispose();
			}
			return image;
		}
		catch (Exception ex)
		{
			log.warn("Failed rasterizing SVG {}", path, ex);
			return null;
		}
	}
}
