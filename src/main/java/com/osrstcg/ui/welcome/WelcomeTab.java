package com.osrstcg.ui.welcome;

import com.osrstcg.ui.layout.SidebarLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
/**
 * Renders the sidebar's first-run Welcome tab: a stack of styled, centered paragraphs sourced from
 * {@link WelcomeContent}. Swing component; must be built on the EDT.
 */
public final class WelcomeTab
{
	private final WelcomeContent catalog;

	public WelcomeTab(WelcomeContent catalog)
	{
		this.catalog = catalog;
	}
/** Adds the paragraph stack to {@code target}'s north region, wrapped to {@code contentMaxW}. */
	public void render(JPanel target, int contentMaxW)
	{
		target.add(buildBlurb(contentMaxW), java.awt.BorderLayout.NORTH);
	}
/** Builds a vertical stack of styled {@link javax.swing.JTextPane}s, one per paragraph, sized to fit {@code contentMaxW}. */
	private JPanel buildBlurb(int contentMaxW)
	{
		int w = Math.max(1, contentMaxW);

		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setOpaque(false);
		wrap.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(8, 0, 6, 0));

		List<WelcomeParagraph> paragraphs = catalog.getParagraphs();
		for (int i = 0; i < paragraphs.size(); i++)
		{
			WelcomeParagraph paragraph = paragraphs.get(i);
			String text = paragraph.getText();
			Color color = WelcomeContent.resolveColor(paragraph.getColor());
			boolean bold = WelcomeContent.isBold(paragraph.getBold());
			int fontSize = WelcomeContent.resolveFontSize(paragraph.getSize());
			int topGap = i == 0 ? 0 : 10;
			wrap.add(buildTextArea(w, text, topGap, color, bold, fontSize));
		}

		int totalH = wrap.getPreferredSize().height;
		wrap.setPreferredSize(new Dimension(w, totalH));
		wrap.setMaximumSize(new Dimension(w, totalH));
		return wrap;
	}
/** Builds a single non-editable, center-aligned, word-wrapped paragraph text pane sized to its rendered height at {@code contentMaxW}. */
	private static JTextPane buildTextArea(
		int contentMaxW, String text, int topGap, Color foreground, boolean bold, int fontSize)
	{
		int w = Math.max(1, contentMaxW);
		Font font = SidebarLayout.resolveWelcomeFont(bold, fontSize);

		JTextPane tp = new JTextPane();
		tp.setEditable(false);
		tp.setOpaque(false);
		tp.setFocusable(false);
		tp.setForeground(foreground);
		tp.setFont(font);
		tp.setText(text == null ? "" : text);
		tp.setBorder(new EmptyBorder(topGap, 0, 0, 0));
		tp.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

		SimpleAttributeSet attrs = new SimpleAttributeSet();
		StyleConstants.setAlignment(attrs, StyleConstants.ALIGN_CENTER);
		StyleConstants.setFontFamily(attrs, font.getFamily());
		StyleConstants.setFontSize(attrs, font.getSize());
		StyleConstants.setForeground(attrs, foreground);
		StyledDocument doc = tp.getStyledDocument();
		doc.setParagraphAttributes(0, doc.getLength(), attrs, false);
		doc.setCharacterAttributes(0, doc.getLength(), attrs, false);

		tp.setSize(w, Short.MAX_VALUE);
		int bodyH = tp.getPreferredSize().height;
		tp.setPreferredSize(new Dimension(w, bodyH));
		tp.setMaximumSize(new Dimension(w, bodyH));
		return tp;
	}
}
