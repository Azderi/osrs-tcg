package com.osrstcg.ui.account;

import com.osrstcg.cloud.api.CloudApiException;
import com.osrstcg.cloud.session.CloudSessionService;
import com.osrstcg.state.TcgStateService;
import com.osrstcg.ui.save.MigrateSavePicker;
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

@Slf4j
public final class MigrateCollectionController
{
	public static final String MIGRATE_COLLECTION_PROMPT =
		"Your local collection is waiting to be moved to the OSRS TCG server! Click the button below to migrate. The plugin will no longer work locally.";
	public static final String CREATE_PROFILE_PROMPT =
		"OSRS TCG stores collections on a server. You don't seem to have a profile yet. Click the button below to create an OSRS TCG profile for your account.";

	private static final String MIGRATE_COLLECTION_WARNING_INTRO =
		"This will send data to a third-party server not controlled or verified by RuneLite developers.";
	private static final String MIGRATE_COLLECTION_DATA_SENT =
		"• Your RuneScape display name and account identifiers used to link this client to your account\n"
			+ "• Your owned cards and their details\n"
			+ "• Your IP address while connected\n"
			+ "• Information about various in-game events to process credit gains";
	private static final String MIGRATE_COLLECTION_PROGRESS_IMPACT =
		"• Migrated cards are kept as beta cards and cannot be traded or sold\n"
			+ "• Local credits and opened-pack counts are not carried over\n"
			+ "• You can play normally; beta cards appear when import finishes\n"
			+ "• New cards from packs after migrating are normal cards and can be traded";
	private static final String MIGRATE_IS_ONE_SHOT =
		"If you're importing beta cards, it might take some time for them to appear in your collection.";
	private static final int MIGRATE_WARNING_DIALOG_WIDTH = 540;
	private static final int MIGRATE_WARNING_DIALOG_HEIGHT = 345;
	private static final int MIGRATE_WARNING_CONTENT_WIDTH = 500;
	private static final String PRIVACY_URL = "https://osrs-tcg.net/privacy";

	private final CloudSessionService cloudSessionService;
	private final TcgStateService stateService;
	private final MigrateSavePicker migrateSavePicker;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Component dialogParent;
	private final Runnable refreshUi;
	private final Runnable onMigrateSuccessSelectOverview;
	private final Runnable afterMigrateUi;
	private final AtomicBoolean migrateInFlight = new AtomicBoolean(false);

	public MigrateCollectionController(
		CloudSessionService cloudSessionService,
		TcgStateService stateService,
		MigrateSavePicker migrateSavePicker,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Component dialogParent,
		Runnable refreshUi,
		Runnable onMigrateSuccessSelectOverview,
		Runnable afterMigrateUi)
	{
		this.cloudSessionService = cloudSessionService;
		this.stateService = stateService;
		this.migrateSavePicker = migrateSavePicker;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.dialogParent = dialogParent;
		this.refreshUi = refreshUi;
		this.onMigrateSuccessSelectOverview = onMigrateSuccessSelectOverview;
		this.afterMigrateUi = afterMigrateUi;
	}

	public AtomicBoolean inFlight()
	{
		return migrateInFlight;
	}

	public void migrate()
	{
		boolean create = cloudSessionService.needsProfileCreate();
		boolean pending = create || cloudSessionService.isMigrationPending();
		if (cloudSessionService.isRestrictedWorld() || !pending)
		{
			afterMigrateUi.run();
			return;
		}
		if (!confirmMigrateCollection())
		{
			return;
		}

		if (stateService.hasDiskSaves())
		{
			migrateSavePicker.showMigratePicker(
				entry ->
				{
					if (!stateService.applyDiskSaveForMigrate(entry.getName(), entry.getSourceDir()))
					{
						TcgPluginGameMessages.queueGameMessage(chatMessageManager,
							"[OSRS TCG] Failed to load the selected save for migrate "
								+ "(debug-mode saves cannot be uploaded).");
						return;
					}
					refreshUi.run();
					beginMigrateUpload(false);
				},
				null);
			return;
		}

		beginMigrateUpload(create);
	}

	public void beginMigrateUpload(boolean create)
	{
		if (!migrateInFlight.compareAndSet(false, true))
		{
			return;
		}
		afterMigrateUi.run();
		scheduler.execute(() ->
		{
			boolean success = false;
			try
			{
				cloudSessionService.migrateLocalCollection(!create);
				success = true;
			}
			catch (CloudApiException ex)
			{
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] " + (create ? "Create profile" : "Migrate") + " failed: " + ex.getMessage());
			}
			catch (Exception ex)
			{
				log.warn(create ? "Create profile failed" : "Migrate collection failed", ex);
				String detail = ex.getMessage() != null && !ex.getMessage().isBlank()
					? ex.getMessage()
					: "try again";
				TcgPluginGameMessages.queueGameMessage(chatMessageManager,
					"[OSRS TCG] " + (create ? "Create profile" : "Migrate") + " failed: " + detail);
			}
			finally
			{
				migrateInFlight.set(false);
				boolean goOverview = success;
				SwingUtilities.invokeLater(() ->
				{
					if (goOverview)
					{
						onMigrateSuccessSelectOverview.run();
					}
					afterMigrateUi.run();
					refreshUi.run();
				});
			}
		});
	}

	private boolean confirmMigrateCollection()
	{
		JPanel sections = new JPanel();
		sections.setOpaque(false);
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.add(buildMigrateWarningSection("Attention!", MIGRATE_COLLECTION_WARNING_INTRO, true));
		sections.add(Box.createVerticalStrut(10));
		sections.add(buildMigrateWarningSection("What is sent?", MIGRATE_COLLECTION_DATA_SENT, false));
		sections.add(Box.createVerticalStrut(10));
		sections.add(buildMigrateWarningSection("What happens to your progress?", MIGRATE_COLLECTION_PROGRESS_IMPACT, false));
		sections.add(Box.createVerticalStrut(10));
		sections.add(buildMigrateWarningNoteGroup(MIGRATE_IS_ONE_SHOT));
		sections.add(Box.createVerticalStrut(6));

		JScrollPane scroll = new JScrollPane(sections);
		scroll.setBorder(null);
		scroll.getViewport().setOpaque(false);
		scroll.setOpaque(false);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		scroll.setPreferredSize(new Dimension(MIGRATE_WARNING_DIALOG_WIDTH, MIGRATE_WARNING_DIALOG_HEIGHT));

		int choice = JOptionPane.showConfirmDialog(
			dialogParent,
			scroll,
			"OSRS TCG cloud",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.YES_OPTION;
	}

	private JPanel buildMigrateWarningSection(String title, String body, boolean includePrivacyLink)
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
		sizeMigrateWarningText(text, MIGRATE_WARNING_CONTENT_WIDTH);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(header);
		section.add(text);
		if (includePrivacyLink)
		{
			section.add(buildMigratePrivacyLink());
		}
		int sectionH = Math.max(1, section.getPreferredSize().height);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, sectionH));
		return section;
	}

	private static void sizeMigrateWarningText(JTextArea text, int width)
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

	private JLabel buildMigratePrivacyLink()
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

	private JLabel buildMigrateWarningNoteGroup(String line)
	{
		JLabel label = new JLabel(line);
		label.setFont(FontManager.getRunescapeFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	public static JTextPane createMigratePromptPane()
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

	public void updatePromptLayout(JTextPane migratePromptPane, JPanel migrateFooterWrap, int width)
	{
		if (migratePromptPane == null)
		{
			return;
		}
		String prompt = cloudSessionService.needsProfileCreate()
			? CREATE_PROFILE_PROMPT
			: MIGRATE_COLLECTION_PROMPT;
		migratePromptPane.setText(prompt);
		javax.swing.text.SimpleAttributeSet attrs = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setAlignment(attrs, javax.swing.text.StyleConstants.ALIGN_CENTER);
		javax.swing.text.StyleConstants.setFontFamily(attrs, migratePromptPane.getFont().getFamily());
		javax.swing.text.StyleConstants.setFontSize(attrs, migratePromptPane.getFont().getSize());
		javax.swing.text.StyleConstants.setForeground(attrs, Color.YELLOW);
		javax.swing.text.StyledDocument doc = migratePromptPane.getStyledDocument();
		doc.setParagraphAttributes(0, doc.getLength(), attrs, false);
		doc.setCharacterAttributes(0, doc.getLength(), attrs, false);

		migratePromptPane.setSize(width, Short.MAX_VALUE);
		int height = Math.max(1, migratePromptPane.getPreferredSize().height);
		Dimension size = new Dimension(width, height);
		migratePromptPane.setMinimumSize(size);
		migratePromptPane.setPreferredSize(size);
		migratePromptPane.setMaximumSize(size);

		migrateFooterWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		migrateFooterWrap.setMinimumSize(null);
		migrateFooterWrap.setPreferredSize(null);
		migrateFooterWrap.setMaximumSize(null);
		int wrapH = Math.max(1, migrateFooterWrap.getPreferredSize().height);
		migrateFooterWrap.setMinimumSize(new Dimension(0, wrapH));
		migrateFooterWrap.setPreferredSize(new Dimension(width, wrapH));
		migrateFooterWrap.setMaximumSize(new Dimension(width, wrapH));
	}

	public void updateButtonState(JButton migrateCollectionButton)
	{
		if (migrateCollectionButton == null)
		{
			return;
		}
		boolean create = cloudSessionService.needsProfileCreate();
		boolean pending = create || cloudSessionService.isMigrationPending();
		boolean busy = migrateInFlight.get();
		migrateCollectionButton.setEnabled(pending && !busy);
		if (create)
		{
			migrateCollectionButton.setText(busy ? "Creating…" : "Create profile");
		}
		else
		{
			migrateCollectionButton.setText(busy ? "Migrating…" : "Migrate collection");
		}
	}
}
