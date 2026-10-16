package com.osrstcg.ui.collection;

import com.osrstcg.catalog.CardDefinition;
import com.osrstcg.catalog.CardImageCacheService;
import com.osrstcg.catalog.RarityMath;
import com.osrstcg.ui.SharedCardRenderer;
import com.osrstcg.ui.card.CardFaceDrawRequest;
import com.osrstcg.ui.layout.SidebarLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.IntSupplier;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Full rendered card-face preview shown in place of the Collection tab's list when a card name is
 * clicked. Reuses {@link SharedCardRenderer}'s pack-reveal rendering pipeline to draw the same
 * banded/full-art face; static image only (no foil sparkle animation, no per-copy condition/wear),
 * since the Collection tab only tracks owned name/foil aggregates rather than individual pulled
 * copies.
 */
public final class CardPreviewPanel extends JPanel
{
	private final CardImageCacheService imageCacheService;
	private final IntSupplier contentWidth;
	private final CardArea cardArea;

	private CardDefinition card;
	private boolean foil;
	private RarityMath.Tier tier;
	private String artPath;
	private Runnable onBack = () -> { };

	/**
	 * Wires the image cache and the sidebar's live content-width supplier, and builds the back
	 * button/card area. The back button is inert until {@link #setOnBack(Runnable)} is called, since
	 * the "close preview" behavior lives on the controller that owns this panel and may not exist yet
	 * at construction time.
	 */
	public CardPreviewPanel(CardImageCacheService imageCacheService, IntSupplier contentWidth)
	{
		this.imageCacheService = imageCacheService;
		this.contentWidth = contentWidth;

		setLayout(new BorderLayout(0, 8));
		setOpaque(false);

		JButton backButton = new JButton("← Back to collection");
		backButton.setFont(FontManager.getRunescapeSmallFont());
		backButton.setForeground(Color.WHITE);
		backButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		backButton.setFocusable(false);
		SidebarLayout.styleOutlinedButton(backButton, ColorScheme.MEDIUM_GRAY_COLOR, 4, 8, 4, 8);
		backButton.addActionListener(e -> onBack.run());

		cardArea = new CardArea();

		add(backButton, BorderLayout.NORTH);
		add(cardArea, BorderLayout.CENTER);
	}

	/** Sets the action run when the back button is clicked. */
	public void setOnBack(Runnable onBack)
	{
		this.onBack = onBack == null ? () -> { } : onBack;
	}

	/**
	 * Selects the card to render and kicks off an async art load if it isn't cached yet, repainting
	 * once it lands. Must be called on the EDT.
	 */
	public void show(CardDefinition def, boolean isFoil, RarityMath.Tier cardTier)
	{
		this.card = def;
		this.foil = isFoil;
		this.tier = cardTier;
		this.artPath = SharedCardRenderer.resolveArtPath(def, isFoil);
		if (artPath != null && imageCacheService.getCached(artPath) == null)
		{
			imageCacheService.preloadAsync(List.of(artPath))
				.whenComplete((v, ex) -> SwingUtilities.invokeLater(this::repaint));
		}
		revalidate();
		repaint();
	}

	/** Draws the currently selected card's face, sized to fit {@link #contentWidth}'s current value. */
	private final class CardArea extends JPanel
	{
		/** Transparent background so the surrounding sidebar chrome shows through. */
		CardArea()
		{
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (card == null)
			{
				return;
			}

			Rectangle bounds = cardBounds();
			BufferedImage art = artPath == null ? null : imageCacheService.getCached(artPath);
			RarityMath.Tier effectiveTier = tier == null ? RarityMath.Tier.COMMON : tier;

			CardFaceDrawRequest.Builder builder = CardFaceDrawRequest.builder()
				.card(card)
				.foil(foil)
				.rarityColor(effectiveTier.getColor())
				.tierLabel(card.getTierLabel());
			if (art != null)
			{
				builder.art(art).artKey(artPath);
			}
			CardFaceDrawRequest req = builder.build();

			Graphics2D g2 = (Graphics2D) g;
			SharedCardRenderer.prewarmFace(bounds.width, bounds.height, req);
			SharedCardRenderer.drawCardFaceIfCached(g2, bounds, req);
		}

		@Override
		public Dimension getPreferredSize()
		{
			Rectangle bounds = cardBounds();
			return new Dimension(bounds.width, bounds.height);
		}

		/** Card-sized rectangle, as wide as {@link #contentWidth} allows, centered horizontally in this panel. */
		private Rectangle cardBounds()
		{
			int maxWidth = Math.max(1, contentWidth.getAsInt());
			int w = Math.min(maxWidth, Math.max(1, getWidth() > 0 ? getWidth() : maxWidth));
			int h = (int) Math.round(w * (SharedCardRenderer.DEFAULT_CARD_HEIGHT / (double) SharedCardRenderer.DEFAULT_CARD_WIDTH));
			int x = Math.max(0, (getWidth() - w) / 2);
			return new Rectangle(x, 0, w, h);
		}
	}
}
