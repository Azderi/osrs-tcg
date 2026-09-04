package com.osrstcg.ui.account;

import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.util.TcgPluginGameMessages;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;
/**
 * Drives the sidebar "create profile" flow: shows a cloud-consent dialog, submits profile creation
 * to the cloud service on a background thread, and updates the create-profile button/prompt UI.
 * UI-touching methods must run on, and callback to, the Swing EDT.
 */
@Slf4j
public final class CreateProfileController
{
	public static final String CREATE_PROFILE_PROMPT =
		"OSRS TCG stores collections on a server. You don't seem to have a profile yet. Click the button below to create an OSRS TCG profile for your account.";

	private static final String CONSENT_WARNING_INTRO =
		"This will send data to a third-party server not controlled or verified by RuneLite developers.";
	private static final String CONSENT_DATA_SENT =
		"• Your RuneScape display name and account identifiers used to link this client to your account\n"
			+ "• Your owned cards and their details\n"
			+ "• Your IP address while connected\n"
			+ "• Information about various in-game events to process credit gains";
	private static final int CONSENT_DIALOG_WIDTH = 540;
	private static final int CONSENT_DIALOG_HEIGHT = 220;
	private static final int CONSENT_CONTENT_WIDTH = 500;
	private static final String PRIVACY_URL = "https://osrs-tcg.net/privacy";

	private final CloudSessionService cloudSessionService;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Component dialogParent;
	private final Runnable refreshUi;
	private final Runnable onSuccessSelectOverview;
	private final Runnable onSuccessOpenAlbum;
	private final Runnable afterUi;
	private final AtomicBoolean inFlight = new AtomicBoolean(false);
/**
	 * @param dialogParent parent component for the consent dialog
	 * @param refreshUi rerun after profile creation finishes, regardless of outcome
	 * @param onSuccessSelectOverview run (before {@code afterUi}) if creation succeeded
	 * @param onSuccessOpenAlbum optional, run after {@code refreshUi} if creation succeeded
	 * @param afterUi run once the create-profile flow has been dispatched or completed, to update UI state
	 */
	public CreateProfileController(
		CloudSessionService cloudSessionService,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Component dialogParent,
		Runnable refreshUi,
		Runnable onSuccessSelectOverview,
		Runnable onSuccessOpenAlbum,
		Runnable afterUi)
	{
		this.cloudSessionService = cloudSessionService;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.dialogParent = dialogParent;
		this.refreshUi = refreshUi;
		this.onSuccessSelectOverview = onSuccessSelectOverview;
		this.onSuccessOpenAlbum = onSuccessOpenAlbum;
		this.afterUi = afterUi;
	}
/**
	 * Entry point for the "create profile" button. Skips straight to {@code afterUi} if consent
	 * isn't needed (restricted world or already consented); otherwise shows the consent dialog and,
	 * on acceptance, starts profile creation.
	 */
	public void createProfile()
	{
		if (cloudSessionService.isRestrictedWorld() || !cloudSessionService.needsCloudConsent())
		{
			afterUi.run();
			return;
		}
		if (!confirmConsent())
		{
			return;
		}
		beginCreate();
	}
/**
	 * Submits {@link CloudSessionService#createProfile()} on {@link #scheduler}, guarded against
	 * concurrent invocations, then marshals the success/failure callbacks back onto the EDT.
	 */
	private void beginCreate()
	{
		if (!inFlight.compareAndSet(false, true))
		{
			return;
		}
		afterUi.run();
		scheduler.execute(() ->
		{
			boolean success = false;
			try
			{
				cloudSessionService.createProfile();
				success = true;
			}
			catch (CloudApiException ex)
			{
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Create profile failed: " + ex.getMessage());
			}
			catch (Exception ex)
			{
				log.warn("Create profile failed", ex);
				String detail = ex.getMessage() != null && !ex.getMessage().isBlank()
					? ex.getMessage()
					: "try again";
				TcgPluginGameMessages.queuePrefixedGameMessage(chatMessageManager,
					"Create profile failed: " + detail);
			}
			finally
			{
				inFlight.set(false);
				boolean goOverview = success;
				SwingUtilities.invokeLater(() ->
				{
					if (goOverview)
					{
						onSuccessSelectOverview.run();
					}
					afterUi.run();
					refreshUi.run();
					if (goOverview && onSuccessOpenAlbum != null)
					{
						onSuccessOpenAlbum.run();
					}
				});
			}
		});
	}
/** Shows the cloud data-consent dialog and returns whether the user accepted. */
	private boolean confirmConsent()
	{
		JPanel sections = new JPanel();
		sections.setOpaque(false);
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.add(buildConsentSection("Attention!", CONSENT_WARNING_INTRO, true));
		sections.add(Box.createVerticalStrut(10));
		sections.add(buildConsentSection("What is sent?", CONSENT_DATA_SENT, false));

		JScrollPane scroll = new JScrollPane(sections);
		scroll.setBorder(null);
		scroll.getViewport().setOpaque(false);
		scroll.setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		scroll.setPreferredSize(new Dimension(CONSENT_DIALOG_WIDTH, CONSENT_DIALOG_HEIGHT));

		int choice = JOptionPane.showConfirmDialog(
			dialogParent,
			scroll,
			"OSRS TCG cloud",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.YES_OPTION;
	}
/** Builds one titled, word-wrapped section of the consent dialog, optionally with a privacy-policy link. */
	private JPanel buildConsentSection(String title, String body, boolean includePrivacyLink)
	{
		JLabel header = new JLabel(title);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(ColorScheme.BRAND_ORANGE);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setBorder(new EmptyBorder(0, 0, 4, 0));
		JTextArea text = new JTextArea(body);
		text.setEditable(false);
		text.setOpaque(false);
		text.setFocusable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setFont(FontManager.getRunescapeFont());
		text.setForeground(Color.WHITE);
		text.setAlignmentX(Component.LEFT_ALIGNMENT);
		sizeConsentText(text, CONSENT_CONTENT_WIDTH);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(header);
		section.add(text);
		if (includePrivacyLink)
		{
			section.add(buildPrivacyLink());
		}
		int sectionH = Math.max(1, section.getPreferredSize().height);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, sectionH));
		return section;
	}
/** Fixes a consent-dialog text area's size to its wrapped-text preferred height at the given width. */
	private static void sizeConsentText(JTextArea text, int width)
	{
		text.setBorder(null);
		text.setMargin(new Insets(0, 0, 0, 0));
		text.setSize(width, Short.MAX_VALUE);
		javax.swing.text.View view = text.getUI().getRootView(text);
		view.setSize(width, Integer.MAX_VALUE);
		int height = Math.max(1, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
		Dimension size = new Dimension(width, height);
		text.setMinimumSize(size);
		text.setPreferredSize(size);
		text.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
	}
/** Builds an underlined, clickable "Privacy policy" label that opens {@link #PRIVACY_URL} in the browser. */
	private JLabel buildPrivacyLink()
	{
		Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
		JLabel link = new JLabel("<html><u>Privacy policy</u></html>");
		link.setFont(FontManager.getRunescapeFont());
		link.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		link.setCursor(hand);
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		link.setToolTipText(PRIVACY_URL);
		link.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				link.setCursor(hand);
			}

			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					LinkBrowser.browse(PRIVACY_URL);
				}
			}
		});
		return link;
	}
/** Creates an empty, non-editable text pane styled for the "create profile" sidebar prompt. */
	public static JTextPane createPromptPane()
	{
		JTextPane tp = new JTextPane();
		tp.setEditable(false);
		tp.setOpaque(false);
		tp.setFocusable(false);
		tp.setForeground(Color.YELLOW);
		tp.setFont(FontManager.getRunescapeSmallFont());
		tp.setBorder(null);
		tp.setAlignmentX(Component.CENTER_ALIGNMENT);
		return tp;
	}
/** Sets the prompt text and center-aligned yellow styling, then resizes the pane and its footer wrapper to fit at the given width. */
	public void updatePromptLayout(JTextPane promptPane, JPanel footerWrap, int width)
	{
		if (promptPane == null)
		{
			return;
		}
		promptPane.setText(CREATE_PROFILE_PROMPT);
		javax.swing.text.SimpleAttributeSet attrs = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setAlignment(attrs, javax.swing.text.StyleConstants.ALIGN_CENTER);
		javax.swing.text.StyleConstants.setFontFamily(attrs, promptPane.getFont().getFamily());
		javax.swing.text.StyleConstants.setFontSize(attrs, promptPane.getFont().getSize());
		javax.swing.text.StyleConstants.setForeground(attrs, Color.YELLOW);
		javax.swing.text.StyledDocument doc = promptPane.getStyledDocument();
		doc.setParagraphAttributes(0, doc.getLength(), attrs, false);
		doc.setCharacterAttributes(0, doc.getLength(), attrs, false);

		promptPane.setSize(width, Short.MAX_VALUE);
		int height = Math.max(1, promptPane.getPreferredSize().height);
		Dimension size = new Dimension(width, height);
		promptPane.setMinimumSize(size);
		promptPane.setPreferredSize(size);
		promptPane.setMaximumSize(size);

		footerWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		footerWrap.setMinimumSize(null);
		footerWrap.setPreferredSize(null);
		footerWrap.setMaximumSize(null);
		int wrapH = Math.max(1, footerWrap.getPreferredSize().height);
		footerWrap.setMinimumSize(new Dimension(0, wrapH));
		footerWrap.setPreferredSize(new Dimension(width, wrapH));
		footerWrap.setMaximumSize(new Dimension(width, wrapH));
	}
/** Enables the create-profile button only while consent is still pending and no request is in flight. */
	public void updateButtonState(JButton createProfileButton)
	{
		if (createProfileButton == null)
		{
			return;
		}
		boolean pending = cloudSessionService.needsCloudConsent();
		boolean busy = inFlight.get();
		createProfileButton.setEnabled(pending && !busy);
		createProfileButton.setText(busy ? "Creating…" : "Create profile");
	}
}
