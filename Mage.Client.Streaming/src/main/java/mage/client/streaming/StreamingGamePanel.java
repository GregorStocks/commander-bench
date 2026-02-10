package mage.client.streaming;

import mage.abilities.icon.CardIconRenderSettings;
import mage.cards.Card;
import mage.cards.MageCard;
import mage.cards.MageCardLocation;
import mage.client.MagePane;
import mage.client.SessionHandler;
import mage.client.cards.Cards;
import mage.client.chat.ChatPanelBasic;
import mage.client.dialog.MageDialog;
import mage.client.dialog.PreferencesDialog;
import mage.client.game.ExilePanel;
import mage.client.game.GamePanel;
import mage.client.game.GraveyardPanel;
import mage.client.game.HandPanel;
import mage.client.game.PlayAreaPanel;
import mage.client.game.PlayAreaPanelOptions;
import mage.client.game.PlayerPanelExt;
import mage.client.components.HoverButton;
import mage.client.plugins.adapters.MageActionCallback;
import mage.client.plugins.impl.Plugins;
import mage.client.util.CardsViewUtil;
import mage.client.util.GUISizeHelper;
import mage.client.util.ImageHelper;
import mage.constants.PlayerAction;
import mage.constants.Zone;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.CommanderView;
import mage.view.CommandObjectView;
import mage.view.CounterView;
import mage.view.GameView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.SimpleCardsView;
import org.apache.log4j.Logger;
import org.mage.plugins.card.images.ImageCache;
import org.mage.plugins.card.images.ImageCacheData;

import mage.client.streaming.recording.FrameCaptureService;
import mage.client.streaming.recording.FFmpegEncoder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Streaming-optimized game panel that automatically requests hand permission
 * from all players when watching a game, and displays all visible hands
 * directly in each player's play area.
 */
public class StreamingGamePanel extends GamePanel {

    private static final Logger logger = Logger.getLogger(StreamingGamePanel.class);

    private final Set<UUID> permissionsRequested = new HashSet<>();
    private UUID streamingGameId;
    private final RoundTracker roundTracker = new RoundTracker();
    private GameView lastGame;
    private boolean handContainerHidden = false;

    // Recording support
    private FrameCaptureService frameCaptureService;
    private Path recordingPath;
    private Thread shutdownHook;

    // Combined chat panel support
    private CombinedChatPanel combinedChatPanel;
    private boolean chatPanelReplaced = false;

    // Hand card caching for incremental updates (eliminates flashing)
    private final Map<UUID, Set<UUID>> lastHandCardIds = new HashMap<>();
    private boolean handPanelsInitialized = false;

    // Zone panels injected into each player's west panel (replacing upstream panels)
    private final Map<UUID, CommanderPanel> commanderPanels = new HashMap<>();
    private final Map<UUID, StreamingGraveyardPanel> streamingGraveyardPanels = new HashMap<>();
    private final Map<UUID, StreamingExilePanel> streamingExilePanels = new HashMap<>();
    private boolean zonePanelsInjected = false;

    // Commander avatar replacement (player UUID -> commander UUID that was used)
    private final Map<UUID, UUID> playerCommanderAvatars = new HashMap<>();

    // Popup auto-dismissal tracking (dialog keys that already have a dismiss timer scheduled)
    private final Set<String> scheduledDismissals = new HashSet<>();

    // LLM cost display support
    private final Map<UUID, JLabel> costLabels = new HashMap<>();
    private final Map<String, Double> playerCosts = new HashMap<>();
    private Timer costPollTimer;
    private Path gameDirPath;
    private final Set<String> chatterboxPlayerNames = new HashSet<>();
    private boolean costPollingInitialized = false;

    // Overlay publishing support
    private static final int OVERLAY_PUSH_INTERVAL_MS = 200;
    private long lastOverlayPushMs = 0L;

    // Game event JSONL logging
    private PrintWriter gameEventWriter;
    private int gameEventSeq = 0;
    private String lastSnapshotKey = "";  // For deduplication
    private static final ZoneId LOG_TZ = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter LOG_TS_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public synchronized void watchGame(UUID currentTableId, UUID parentTableId, UUID gameId, MagePane gamePane) {
        this.streamingGameId = gameId;
        replaceChatWithCombinedPanel();  // Replace before super connects chat
        super.watchGame(currentTableId, parentTableId, gameId, gamePane);
    }

    /**
     * Replace the default chat panels with streaming-optimized versions.
     * Player chat (top) is kept separate from game log (bottom, with spam filtering).
     * This must be called BEFORE super.watchGame() which connects the chat to the server.
     */
    private void replaceChatWithCombinedPanel() {
        if (chatPanelReplaced) {
            return;
        }

        try {
            // Player chat panel (top) - shows player messages, no input for observers
            ChatPanelBasic playerChatPanel = new ChatPanelBasic();
            playerChatPanel.useExtendedView(ChatPanelBasic.VIEW_MODE.GAME);
            playerChatPanel.disableInput();

            // Game log panel (bottom) - filters spammy game messages and routes TALK to top
            combinedChatPanel = new CombinedChatPanel();
            combinedChatPanel.setPlayerChatPanel(playerChatPanel);
            combinedChatPanel.setRoundTracker(roundTracker);
            combinedChatPanel.setGamePanel(this);

            // Access fields via reflection (matching existing pattern in this class)
            Field gameChatField = GamePanel.class.getDeclaredField("gameChatPanel");
            gameChatField.setAccessible(true);
            Field userChatField = GamePanel.class.getDeclaredField("userChatPanel");
            userChatField.setAccessible(true);

            // Replace panel references (before super.watchGame connects chat)
            gameChatField.set(this, combinedChatPanel);
            userChatField.set(this, playerChatPanel);

            // Update the split pane components (keep the split layout)
            Field splitChatField = GamePanel.class.getDeclaredField("splitChatAndLogs");
            splitChatField.setAccessible(true);
            JSplitPane splitChat = (JSplitPane) splitChatField.get(this);
            if (splitChat != null) {
                splitChat.setTopComponent(playerChatPanel);
                splitChat.setBottomComponent(combinedChatPanel);
                splitChat.setResizeWeight(0.5);  // Split evenly between chat and game log
            }

            chatPanelReplaced = true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to setup chat panels", e);
        }
    }

    @Override
    public synchronized void init(int messageId, GameView game, boolean callGameUpdateAfterInit) {
        // Adjust battlefield card size bounds before the first layout
        adjustBattlefieldCardSizes();
        super.init(messageId, game, callGameUpdateAfterInit);
        this.lastGame = game;
        roundTracker.update(game);
        // Hide the central hand container (we show hands in play areas instead)
        hideHandContainer();
        requestHandPermissions(game);
        initCostPolling();
        initGameEventLog();
        // Schedule auto-dismissal of any popup dialogs created during init
        schedulePopupDismissal();
        pushOverlayState(game, true);
        writeStateSnapshotIfChanged(game);
    }

    @Override
    public synchronized void updateGame(int messageId, GameView game) {
        super.updateGame(messageId, game);
        this.lastGame = game;
        roundTracker.update(game);
        // Schedule auto-dismissal of any popup dialogs created by the parent
        schedulePopupDismissal();
        // Hide the central hand container (we show hands in play areas instead)
        hideHandContainer();
        // Also try to request permissions on updates in case we missed init
        requestHandPermissions(game);
        // Distribute hands to each player's PlayAreaPanel
        distributeHands(game);
        // Inject streaming zone panels (commander, graveyard, exile) into west panel
        // Must happen before distributing cards to those panels
        injectZonePanels(game);
        // Distribute zone cards to the streaming panels
        distributeGraveyards(game);
        distributeExile(game);
        distributeCommanders(game);
        // Replace default avatars with commander card art
        replaceAvatarsWithCommanderArt(game);
        // Clean up player panels (hide redundant elements)
        updatePlayerPanelVisibility(game);
        // Re-layout stack cards vertically (parent lays them out horizontally)
        relayoutStackVertically();
        writeStateSnapshotIfChanged(game);
        pushOverlayState(game, false);
    }

    /**
     * Override the no-arg updateGame to restore dead player panel sizes.
     * The parent's updateGame() contains the collapse code that shrinks eliminated
     * players to 95px. By overriding here (rather than only in the 2-arg version),
     * we catch all code paths: the 5-arg updateGame, direct no-arg calls from
     * callbacks, endMessage, ask, select, etc.
     */
    @Override
    public synchronized void updateGame() {
        super.updateGame();
        restoreDeadPlayerPanelSizes();
    }

    /**
     * Override to auto-close the streaming observer after the game ends.
     * Waits 10 seconds then exits, which triggers recording finalization via shutdown hook.
     */
    @Override
    public void endMessage(int messageId, GameView gameView, Map<String, Serializable> options, String message) {
        super.endMessage(messageId, gameView, options, message);
        pushOverlayState(gameView, true);

        if (gameEventWriter != null) {
            JsonObject event = new JsonObject();
            event.addProperty("message", message != null ? message : "");
            writeGameEvent("game_over", event);
            gameEventWriter.close();
            gameEventWriter = null;
        }

        if (costPollTimer != null) {
            costPollTimer.stop();
        }

        logger.info("Game ended, will auto-close in 10 seconds");

        Timer exitTimer = new Timer(10000, e -> {
            logger.info("Auto-closing streaming observer");
            System.exit(0);
        });
        exitTimer.setRepeats(false);
        exitTimer.start();
    }

    /**
     * Override to enable showHandInPlayArea, showGraveyardInPlayArea, and showExileInPlayArea for all players in streaming mode.
     */
    @Override
    protected PlayAreaPanelOptions createPlayAreaPanelOptions(GameView game, PlayerView player, boolean playerItself, boolean topRow) {
        logger.info("Creating PlayAreaPanelOptions for " + player.getName() + " with showExileInPlayArea=true");
        return new PlayAreaPanelOptions(
                game.isPlayer(),
                player.isHuman(),
                playerItself,
                game.isRollbackTurnsAllowed(),
                topRow,
                true,  // showHandInPlayArea enabled for streaming
                true,  // showGraveyardInPlayArea enabled for streaming
                true   // showExileInPlayArea enabled for streaming
        );
    }

    /**
     * Override to suppress exile popup windows in streaming mode.
     * Exile is displayed inline in each player's play area instead.
     */
    @Override
    protected void updateExileWindows(GameView game) {
        // No-op: exile is displayed inline per-player in streaming mode
    }

    /**
     * Undo the parent class behavior that shrinks eliminated players' panels to 95px.
     * In streaming mode we want dead players' board state to remain fully visible.
     */
    private void restoreDeadPlayerPanelSizes() {
        Set<Container> parentsToRevalidate = new HashSet<>();
        for (PlayAreaPanel playArea : getPlayers().values()) {
            Container parent = playArea.getParent();
            if (parent == null || !(parent.getLayout() instanceof GridBagLayout)) {
                continue;
            }
            GridBagLayout layout = (GridBagLayout) parent.getLayout();
            GridBagConstraints gbc = layout.getConstraints(playArea);
            // Parent sets dead players to 0.01 and living players to 0.99;
            // reset all to equal weighting so dead players keep their full area.
            if (Math.abs(gbc.weightx - 0.5) > 0.01) {
                gbc.weightx = 0.5;
                layout.setConstraints(playArea, gbc);
                parentsToRevalidate.add(parent);
            }
            playArea.setPreferredSize(null);
        }
        for (Container parent : parentsToRevalidate) {
            parent.validate();
            parent.repaint();
        }
    }

    /**
     * Hide the entire bottom commands area (hand, feedback, stack, skip buttons).
     * Observers don't need any of these controls.
     * This keeps all streaming-specific UI changes isolated to this class.
     */
    private void hideHandContainer() {
        if (handContainerHidden) {
            return; // Already hidden
        }

        try {
            // Get pnlHelperHandButtonsStackArea which contains the bottom commands area
            Field helperAreaField = GamePanel.class.getDeclaredField("pnlHelperHandButtonsStackArea");
            helperAreaField.setAccessible(true);
            JPanel helperArea = (JPanel) helperAreaField.get(this);

            if (helperArea != null && helperArea.getLayout() instanceof BorderLayout) {
                BorderLayout layout = (BorderLayout) helperArea.getLayout();
                // Find and hide the SOUTH component (pnlCommandsRoot)
                Component southComponent = layout.getLayoutComponent(BorderLayout.SOUTH);
                if (southComponent != null) {
                    southComponent.setVisible(false);
                    helperArea.remove(southComponent);
                }

                // Extract stackObjects from the hidden hierarchy and re-add it
                // so the stack is visible in the Swing UI (and captured in video recordings)
                reparentStackPanel(helperArea);

                helperArea.revalidate();
                helperArea.repaint();
            }

            // Also hide btnSwitchHands
            Field btnSwitchHandsField = GamePanel.class.getDeclaredField("btnSwitchHands");
            btnSwitchHandsField.setAccessible(true);
            JButton btnSwitchHands = (JButton) btnSwitchHandsField.get(this);
            if (btnSwitchHands != null) {
                btnSwitchHands.setVisible(false);
            }

            handContainerHidden = true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to hide hand container via reflection", e);
        }

        hideBigCardPanel();
    }

    /**
     * Extract the stack panel (stackObjects) from the hidden command area
     * and place it on the right side above the chat/game log panels.
     *
     * Layout: splitBattlefieldAndChats RIGHT was splitChatAndLogs.
     * We wrap it: stack (NORTH) + splitChatAndLogs (CENTER) in a new panel,
     * then set that as the new RIGHT component.
     */
    private void reparentStackPanel(JPanel helperArea) {
        try {
            Field stackField = GamePanel.class.getDeclaredField("stackObjects");
            stackField.setAccessible(true);
            Cards stackPanel = (Cards) stackField.get(this);

            Field splitBFChatField = GamePanel.class.getDeclaredField("splitBattlefieldAndChats");
            splitBFChatField.setAccessible(true);
            JSplitPane splitBFChat = (JSplitPane) splitBFChatField.get(this);

            Field splitChatLogsField = GamePanel.class.getDeclaredField("splitChatAndLogs");
            splitChatLogsField.setAccessible(true);
            JSplitPane splitChatLogs = (JSplitPane) splitChatLogsField.get(this);

            if (stackPanel != null && splitBFChat != null && splitChatLogs != null) {
                // Remove stackPanel from its current (hidden) parent
                Container oldParent = stackPanel.getParent();
                if (oldParent != null) {
                    oldParent.remove(stackPanel);
                }

                // Enable vertical scrolling (parent disables it for horizontal layout)
                Field scrollField = Cards.class.getDeclaredField("jScrollPane1");
                scrollField.setAccessible(true);
                JScrollPane scrollPane = (JScrollPane) scrollField.get(stackPanel);
                if (scrollPane != null) {
                    scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
                    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                }

                stackPanel.setVisible(true);

                // Create wrapper: stack on top, chat/logs below
                JPanel rightWrapper = new JPanel(new BorderLayout());
                rightWrapper.setOpaque(false);
                rightWrapper.add(stackPanel, BorderLayout.NORTH);
                rightWrapper.add(splitChatLogs, BorderLayout.CENTER);

                // Ensure the right panel is wide enough to show a card
                int minWidth = GUISizeHelper.handCardDimension.width + 30;
                rightWrapper.setMinimumSize(new Dimension(minWidth, 0));

                splitBFChat.setRightComponent(rightWrapper);

                // Set the divider so the right panel gets ~20% of width
                // (deferred so the panel has its final size)
                SwingUtilities.invokeLater(() -> {
                    int totalWidth = splitBFChat.getWidth();
                    if (totalWidth > 0) {
                        splitBFChat.setDividerLocation(totalWidth - Math.max(minWidth, totalWidth / 5));
                    }
                });
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to reparent stack panel", e);
        }
    }

    /**
     * Re-layout stack cards vertically with overlap, instead of the default
     * horizontal layout. Called after each game update since the parent's
     * displayStack() resets to horizontal.
     */
    private void relayoutStackVertically() {
        try {
            Field stackField = GamePanel.class.getDeclaredField("stackObjects");
            stackField.setAccessible(true);
            Cards stackCards = (Cards) stackField.get(this);
            if (stackCards == null) {
                return;
            }

            Field cardAreaField = Cards.class.getDeclaredField("cardArea");
            cardAreaField.setAccessible(true);
            JPanel cardArea = (JPanel) cardAreaField.get(stackCards);
            if (cardArea == null) {
                return;
            }

            List<MageCard> cardsToLayout = new ArrayList<>();
            for (Component c : cardArea.getComponents()) {
                if (c instanceof MageCard) {
                    cardsToLayout.add((MageCard) c);
                }
            }

            // Use the configured card dimension for sizing (even when empty)
            Dimension cardDim = GUISizeHelper.handCardDimension;
            int cardWidth = cardDim.width;
            int cardHeight = cardDim.height;
            int panelHeight = (int) (cardHeight * 1.5);

            if (cardsToLayout.isEmpty()) {
                stackCards.setPreferredSize(new Dimension(0, panelHeight));
                stackCards.revalidate();
                return;
            }
            int overlapGap = (int) (cardHeight * 0.4);
            int margin = 4;

            int dy = margin;
            for (MageCard card : cardsToLayout) {
                card.setCardLocation(margin, dy);
                dy += overlapGap;
            }
            // Last card is fully visible
            int totalHeight = dy - overlapGap + cardHeight + margin;

            cardArea.setPreferredSize(new Dimension(cardWidth + margin * 2, totalHeight));
            cardArea.revalidate();

            stackCards.setPreferredSize(new Dimension(0, panelHeight));
            stackCards.revalidate();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to re-layout stack vertically", e);
        }
    }

    /**
     * Hide the card preview panel on the right side.
     * Observers don't need the enlarged card preview.
     */
    private void hideBigCardPanel() {
        try {
            // Hide the bigCardPanel
            Field bigCardPanelField = GamePanel.class.getDeclaredField("bigCardPanel");
            bigCardPanelField.setAccessible(true);
            JPanel bigCardPanel = (JPanel) bigCardPanelField.get(this);
            if (bigCardPanel != null) {
                bigCardPanel.setVisible(false);
            }

            // Set the split pane divider to give all space to the game area
            Field splitField = GamePanel.class.getDeclaredField("splitGameAndBigCard");
            splitField.setAccessible(true);
            JSplitPane splitPane = (JSplitPane) splitField.get(this);
            if (splitPane != null) {
                splitPane.setDividerLocation(1.0);  // 100% to left component
                splitPane.setDividerSize(0);  // Hide the divider
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to hide big card panel via reflection", e);
        }
    }

    @Override
    public void onActivated() {
        // Remove the hand/stack splitter from restoration before activating
        // This prevents restoreSplitters() from overriding our hideHandContainer() changes
        removeSplitterFromRestore();
        super.onActivated();
    }

    private void removeSplitterFromRestore() {
        try {
            Field splittersField = GamePanel.class.getDeclaredField("splitters");
            splittersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> splitters = (Map<String, ?>) splittersField.get(this);
            splitters.remove(PreferencesDialog.KEY_GAMEPANEL_DIVIDER_LOCATIONS_HAND_STACK);
            splitters.remove(PreferencesDialog.KEY_GAMEPANEL_DIVIDER_LOCATIONS_GAME_AND_BIG_CARD);
            splitters.remove(PreferencesDialog.KEY_GAMEPANEL_DIVIDER_LOCATIONS_CHAT_AND_LOGS);
            splitters.remove(PreferencesDialog.KEY_GAMEPANEL_DIVIDER_LOCATIONS_BATTLEFIELD_AND_CHATS);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to remove splitters from restore", e);
        }
    }

    /**
     * Adjust battlefield card size bounds for streaming mode.
     * Lowers the max so cards aren't enormous on sparse boards,
     * and lowers the min so cards shrink further before overflow kicks in.
     * Must be called before super.init() triggers the first layout.
     */
    private void adjustBattlefieldCardSizes() {
        // Cap max: ~100px wide (default is ~156px). Aspect ratio 312:445.
        GUISizeHelper.battlefieldCardMaxDimension = new Dimension(100, 143);
        // Lower min: ~20px wide (default is ~52px). Allows aggressive shrinking.
        GUISizeHelper.battlefieldCardMinDimension = new Dimension(20, 29);
        // Cap hand card size to match battlefield max so hand cards don't dwarf battlefield cards
        int maxWidth = GUISizeHelper.battlefieldCardMaxDimension.width;
        if (GUISizeHelper.handCardDimension.width > maxWidth) {
            int maxHeight = (int) (maxWidth * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);
            GUISizeHelper.handCardDimension = new Dimension(maxWidth, maxHeight);
        }
        // Propagate to the card layout plugin
        Plugins.instance.changeGUISize();
    }

    /**
     * Schedule auto-dismissal of popup dialogs created by the parent GamePanel.
     * Reflects into the parent's private dialog maps, finds newly-appeared dialogs,
     * and schedules a 15-second timer to hide each one.
     */
    private void schedulePopupDismissal() {
        scheduleDismissalForMap("revealed");
        scheduleDismissalForMap("lookedAt");
        scheduleDismissalForMap("companion");
        scheduleDismissalForMap("graveyardWindows");
        scheduleDismissalForMap("sideboardWindows");
    }

    /**
     * Helper: reflect into a named Map<String, ? extends MageDialog> field in GamePanel,
     * find new entries, and schedule a 15-second dismiss timer for each.
     */
    @SuppressWarnings("unchecked")
    private void scheduleDismissalForMap(String fieldName) {
        try {
            Field field = GamePanel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<String, ? extends MageDialog> map = (Map<String, ? extends MageDialog>) field.get(this);

            for (Map.Entry<String, ? extends MageDialog> entry : map.entrySet()) {
                String key = fieldName + ":" + entry.getKey();
                if (scheduledDismissals.contains(key)) {
                    continue; // Already scheduled
                }
                scheduledDismissals.add(key);

                MageDialog dialog = entry.getValue();
                String dialogKey = entry.getKey();
                Timer dismissTimer = new Timer(15000, e -> {
                    dialog.hideDialog();
                    // Remove from parent's map so it doesn't accumulate
                    try {
                        Field f = GamePanel.class.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Map<String, ?> m = (Map<String, ?>) f.get(this);
                        m.remove(dialogKey);
                    } catch (Exception ex) {
                        // Best effort cleanup
                    }
                    scheduledDismissals.remove(key);
                });
                dismissTimer.setRepeats(false);
                dismissTimer.start();

                logger.info("Scheduled 15s auto-dismiss for " + fieldName + " dialog: " + entry.getKey());
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to schedule dismissal for " + fieldName, e);
        }
    }

    /**
     * Request permission to see hand cards from all players we haven't already asked.
     */
    private void requestHandPermissions(GameView game) {
        if (game == null || game.getPlayers() == null || streamingGameId == null) {
            return;
        }

        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            if (!permissionsRequested.contains(playerId)) {
                permissionsRequested.add(playerId);
                logger.info("Requesting hand permission from player: " + player.getName());
                SessionHandler.sendPlayerAction(
                        PlayerAction.REQUEST_PERMISSION_TO_SEE_HAND_CARDS,
                        streamingGameId,
                        playerId
                );
            }
        }
    }

    /**
     * Distribute hand cards to each player's PlayAreaPanel using incremental updates.
     * This avoids full repaints by only adding/removing cards that actually changed.
     */
    private void distributeHands(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();
        Map<String, Card> loadedCards = getLoadedCards();

        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            PlayAreaPanel playArea = players.get(playerId);
            if (playArea == null) {
                continue;
            }

            HandPanel handPanel = playArea.getHandPanel();
            if (handPanel == null) {
                continue;
            }

            // Enable scale-to-fit for streaming mode on first load
            if (!handPanelsInitialized) {
                handPanel.setScaleToFit(true);
            }

            // Get current hand cards for this player
            CardsView currentHand = getHandCardsForPlayer(player, game, loadedCards);
            Set<UUID> currentIds = currentHand != null ? currentHand.keySet() : Set.of();
            Set<UUID> previousIds = lastHandCardIds.getOrDefault(playerId, Set.of());

            // Check if hand changed
            if (currentIds.equals(previousIds)) {
                // No change, skip update entirely (no flash)
                continue;
            }

            // For initial load (no previous cards), use normal loadCards to ensure proper initialization
            // For subsequent updates, use incremental updates to avoid flashing
            if (previousIds.isEmpty()) {
                // Initial load - use normal path
                if (currentHand != null && !currentHand.isEmpty()) {
                    handPanel.loadCards(currentHand, getBigCard(), getGameId());
                    handPanel.setVisible(true);
                } else {
                    handPanel.setVisible(false);
                }
            } else {
                // Incremental update - avoid full repaint
                updateHandIncrementally(handPanel, currentHand, previousIds, currentIds);
            }

            // Update cache
            lastHandCardIds.put(playerId, new HashSet<>(currentIds));
        }

        handPanelsInitialized = true;
    }

    /**
     * Update hand panel incrementally by only adding/removing changed cards.
     * This bypasses Cards.loadCards() which always triggers full repaints.
     */
    private void updateHandIncrementally(HandPanel handPanel, CardsView currentHand,
                                         Set<UUID> previousIds, Set<UUID> currentIds) {
        try {
            // Access the Cards component inside HandPanel
            Field handField = HandPanel.class.getDeclaredField("hand");
            handField.setAccessible(true);
            Cards hand = (Cards) handField.get(handPanel);

            // Access Cards internals
            Field cardAreaField = Cards.class.getDeclaredField("cardArea");
            cardAreaField.setAccessible(true);
            JPanel cardArea = (JPanel) cardAreaField.get(hand);

            // Access the scroll pane for calculating available width
            Field scrollPaneField = HandPanel.class.getDeclaredField("jScrollPane1");
            scrollPaneField.setAccessible(true);
            JScrollPane scrollPane = (JScrollPane) scrollPaneField.get(handPanel);

            // Get the cards map (public method)
            Map<UUID, MageCard> cardsMap = hand.getMageCardsForUpdate();

            // Compute diff
            Set<UUID> toRemove = new HashSet<>(previousIds);
            toRemove.removeAll(currentIds);

            Set<UUID> toAdd = new HashSet<>(currentIds);
            toAdd.removeAll(previousIds);

            boolean changed = !toRemove.isEmpty() || !toAdd.isEmpty();

            if (!changed) {
                return;
            }

            // Hide card area during update to prevent intermediate states from being visible
            cardArea.setVisible(false);

            // Calculate new card dimension for the updated count
            int newCardCount = currentIds.size();
            Dimension newDimension = calculateScaledCardDimension(scrollPane, newCardCount);

            // Remove cards that are no longer in hand
            for (UUID cardId : toRemove) {
                MageCard card = cardsMap.remove(cardId);
                if (card != null) {
                    cardArea.remove(card);
                }
            }

            // Add new cards at the correct scaled size
            if (currentHand != null) {
                for (UUID cardId : toAdd) {
                    CardView cardView = currentHand.get(cardId);
                    if (cardView != null) {
                        addCardToHandWithDimension(hand, cardArea, cardsMap, cardView, newDimension);
                    }
                }
            }

            // Resize all existing cards to the new dimension
            for (MageCard card : cardsMap.values()) {
                card.setCardBounds(0, 0, newDimension.width, newDimension.height);
            }

            // Layout cards
            layoutHandCards(cardArea, Zone.HAND);

            // Update card area preferred size
            hand.sizeCards(newDimension);

            // Show card area after all changes are complete
            cardArea.setVisible(true);

            // Ensure hand panel is visible if it has cards
            if (!cardsMap.isEmpty()) {
                handPanel.setVisible(true);
            } else {
                handPanel.setVisible(false);
            }

        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to update hand incrementally, falling back to full load", e);
            // Fallback to full load if reflection fails
            if (currentHand != null && !currentHand.isEmpty()) {
                handPanel.loadCards(currentHand, getBigCard(), getGameId());
                handPanel.setVisible(true);
            } else {
                handPanel.setVisible(false);
            }
        }
    }

    /**
     * Calculate the scaled card dimension for a given card count.
     * Replicates HandPanel.recalculateCardScale() logic.
     */
    private Dimension calculateScaledCardDimension(JScrollPane scrollPane, int cardCount) {
        if (cardCount == 0) {
            return GUISizeHelper.handCardDimension;
        }

        int availableWidth = scrollPane.getViewport().getWidth();
        if (availableWidth <= 0) {
            return GUISizeHelper.handCardDimension;
        }

        int gapX = MageActionCallback.HAND_CARDS_BETWEEN_GAP_X;
        int totalMargins = MageActionCallback.HAND_CARDS_MARGINS.getLeft() +
                           MageActionCallback.HAND_CARDS_MARGINS.getRight();
        int totalGaps = (cardCount - 1) * gapX;
        int widthForCards = availableWidth - totalMargins - totalGaps;

        int cardWidth = widthForCards / cardCount;

        // Clamp to reasonable bounds
        int baseWidth = GUISizeHelper.handCardDimension.width;
        int minWidth = baseWidth / 3;
        cardWidth = Math.min(cardWidth, baseWidth);
        cardWidth = Math.max(cardWidth, minWidth);

        int cardHeight = (int) (cardWidth * GUISizeHelper.CARD_WIDTH_TO_HEIGHT_COEF);

        return new Dimension(cardWidth, cardHeight);
    }

    /**
     * Add a single card to the hand panel with a specific dimension.
     * Replicates Cards.addCard() logic without triggering full repaint.
     */
    private void addCardToHandWithDimension(Cards hand, JPanel cardArea, Map<UUID, MageCard> cardsMap,
                                            CardView cardView, Dimension cardDimension) {
        // Create the MageCard component
        MageCard mageCard = Plugins.instance.getMageCard(
                cardView,
                getBigCard(),
                new CardIconRenderSettings(),
                cardDimension,
                getGameId(),
                true,
                true,
                PreferencesDialog.getRenderMode(),
                true
        );

        mageCard.setCardContainerRef(cardArea);
        mageCard.update(cardView);
        mageCard.setZone(Zone.HAND);

        // Set card bounds to match the dimension
        mageCard.setCardBounds(0, 0, cardDimension.width, cardDimension.height);

        // Add to map and panel
        cardsMap.put(cardView.getId(), mageCard);
        cardArea.add(mageCard);

        // Position at end (will be relaid out by layoutHandCards)
        int dx = MageActionCallback.getHandOrStackMargins(Zone.HAND).getLeft();
        for (Component comp : cardArea.getComponents()) {
            if (comp instanceof MageCard && comp != mageCard) {
                MageCard existing = (MageCard) comp;
                dx = Math.max(dx, existing.getCardLocation().getCardX() +
                        existing.getCardLocation().getCardWidth() +
                        MageActionCallback.getHandOrStackBetweenGapX(Zone.HAND));
            }
        }
        mageCard.setCardLocation(dx, MageActionCallback.getHandOrStackMargins(Zone.HAND).getTop());
    }

    /**
     * Layout cards in the hand area.
     * Replicates Cards.layoutCards() logic.
     */
    private void layoutHandCards(JPanel cardArea, Zone zone) {
        List<MageCard> cardsToLayout = new ArrayList<>();
        for (Component component : cardArea.getComponents()) {
            if (component instanceof MageCard) {
                cardsToLayout.add((MageCard) component);
            }
        }

        // Sort by X position
        cardsToLayout.sort(Comparator.comparingInt(cp -> cp.getCardLocation().getCardX()));

        // Relocate cards
        int dx = MageActionCallback.getHandOrStackBetweenGapX(zone);
        for (MageCard card : cardsToLayout) {
            card.setCardLocation(dx, card.getCardLocation().getCardY());
            dx += card.getCardLocation().getCardWidth() + MageActionCallback.getHandOrStackBetweenGapX(zone);
        }
    }

    /**
     * Get the hand cards to display for a specific player from watched hands.
     */
    private CardsView getHandCardsForPlayer(PlayerView player, GameView game, Map<String, Card> loadedCards) {
        String playerName = player.getName();

        // Check watched hands (spectator mode)
        Map<String, SimpleCardsView> watchedHands = game.getWatchedHands();
        if (watchedHands != null && watchedHands.containsKey(playerName)) {
            return CardsViewUtil.convertSimple(watchedHands.get(playerName), loadedCards);
        }

        // No hand available for this player
        return null;
    }

    /**
     * Distribute graveyard cards to each player's streaming graveyard panel.
     */
    private void distributeGraveyards(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        for (PlayerView player : game.getPlayers()) {
            StreamingGraveyardPanel panel = streamingGraveyardPanels.get(player.getPlayerId());
            if (panel == null) {
                continue;
            }

            CardsView graveyardCards = player.getGraveyard();
            if (graveyardCards != null && !graveyardCards.isEmpty()) {
                panel.loadCards(graveyardCards, getBigCard(), getGameId());
            }
        }
    }

    /**
     * Distribute exile cards to each player's streaming exile panel.
     * PlayerView.getExile() already filters cards by ownership.
     */
    private void distributeExile(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        for (PlayerView player : game.getPlayers()) {
            StreamingExilePanel panel = streamingExilePanels.get(player.getPlayerId());
            if (panel == null) {
                continue;
            }

            CardsView exileCards = player.getExile();
            if (exileCards != null && !exileCards.isEmpty()) {
                logger.info("Player " + player.getName() + " has " + exileCards.size() + " exiled cards");
                panel.loadCards(exileCards, getBigCard(), getGameId());
            }
        }
    }

    /**
     * Inject streaming zone panels (commander, graveyard, exile) into each
     * player's west panel, replacing the upstream graveyard/exile panels with
     * wider, labeled versions.  Called once after play areas are created.
     */
    private void injectZonePanels(GameView game) {
        if (zonePanelsInjected || game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();

        for (PlayerView player : game.getPlayers()) {
            PlayAreaPanel playArea = players.get(player.getPlayerId());
            if (playArea == null) {
                continue;
            }

            try {
                // Get the playerPanel to find its parent (the west panel)
                PlayerPanelExt playerPanel = playArea.getPlayerPanel();
                if (playerPanel == null || playerPanel.getParent() == null) {
                    continue;
                }

                Container westPanel = playerPanel.getParent();
                if (!(westPanel instanceof JPanel)) {
                    continue;
                }

                UUID playerId = player.getPlayerId();

                // Remove upstream graveyard and exile panels from the west panel
                GraveyardPanel oldGy = playArea.getGraveyardPanel();
                if (oldGy != null) {
                    westPanel.remove(oldGy);
                }
                ExilePanel oldEx = playArea.getExilePanel();
                if (oldEx != null) {
                    westPanel.remove(oldEx);
                }

                // Create and inject our streaming zone panels
                CommanderPanel commanderPanel = new CommanderPanel();
                commanderPanels.put(playerId, commanderPanel);

                StreamingGraveyardPanel graveyardPanel = new StreamingGraveyardPanel();
                streamingGraveyardPanels.put(playerId, graveyardPanel);

                StreamingExilePanel exilePanel = new StreamingExilePanel();
                streamingExilePanels.put(playerId, exilePanel);

                // Layout: playerPanel (0), commanderPanel (1), graveyardPanel (2), exilePanel (3)
                westPanel.add(commanderPanel, 1);
                westPanel.add(graveyardPanel, 2);
                westPanel.add(exilePanel, 3);

                westPanel.revalidate();
                westPanel.repaint();

                logger.info("Injected zone panels for player: " + player.getName());
            } catch (Exception e) {
                logger.warn("Failed to inject zone panels for player: " + player.getName(), e);
            }
        }

        zonePanelsInjected = true;
    }

    /**
     * Distribute commander cards to each player's CommanderPanel.
     * Filters command objects to only include actual commander cards.
     */
    private void distributeCommanders(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        for (PlayerView player : game.getPlayers()) {
            CommanderPanel panel = commanderPanels.get(player.getPlayerId());
            if (panel == null) {
                continue;
            }

            // Debug: log command object list contents
            java.util.List<CommandObjectView> cmdList = player.getCommandObjectList();
            logger.info("Player " + player.getName() + " command list size: " + cmdList.size());
            for (CommandObjectView obj : cmdList) {
                logger.info("  - " + obj.getClass().getSimpleName() + ": " + obj.getName() + " (id: " + obj.getId() + ")");
            }

            // Filter commandList to only CommanderView instances
            CardsView commanders = new CardsView();
            for (CommandObjectView obj : player.getCommandObjectList()) {
                if (obj instanceof CommanderView) {
                    commanders.put(obj.getId(), (CommanderView) obj);
                }
            }

            logger.info("Player " + player.getName() + " commanders found: " + commanders.size());
            panel.loadCards(commanders, getBigCard(), getGameId());
        }
    }

    /**
     * Replace default player avatars with commander card art.
     * Uses the first CommanderView from each player's command object list
     * to load the card image, crop the art portion, and update the avatar HoverButton.
     */
    private void replaceAvatarsWithCommanderArt(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();

        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            PlayAreaPanel playArea = players.get(playerId);
            if (playArea == null) {
                continue;
            }

            // Find the first CommanderView for this player
            CommanderView commander = null;
            for (CommandObjectView obj : player.getCommandObjectList()) {
                if (obj instanceof CommanderView) {
                    commander = (CommanderView) obj;
                    break;
                }
            }

            if (commander == null) {
                continue;
            }

            // Skip if we already replaced avatar with this commander's art
            UUID commanderId = commander.getId();
            if (commanderId.equals(playerCommanderAvatars.get(playerId))) {
                continue;
            }

            // Get the card image from cache
            ImageCacheData cacheData = ImageCache.getCardImageOriginal(commander);
            BufferedImage cardImage = cacheData != null ? cacheData.getImage() : null;

            if (cardImage == null) {
                // Image not yet loaded/downloaded - will retry on next update
                continue;
            }

            // Crop the art region and resize for avatar (scaled to window)
            BufferedImage artCrop = cropCardArt(cardImage);
            int avatarSize = computeAvatarSize(playArea);
            Rectangle avatarRect = new Rectangle(avatarSize, avatarSize);
            BufferedImage avatarImage = ImageHelper.getResizedImage(artCrop, avatarRect);

            // Update the HoverButton avatar via reflection
            try {
                PlayerPanelExt playerPanel = playArea.getPlayerPanel();
                Field avatarField = PlayerPanelExt.class.getDeclaredField("avatar");
                avatarField.setAccessible(true);
                HoverButton avatar = (HoverButton) avatarField.get(playerPanel);

                if (avatar != null) {
                    avatar.update(
                            player.getName(),
                            avatarImage,
                            avatarImage,
                            avatarImage,
                            avatarImage,
                            avatarRect
                    );
                    avatar.repaint();
                }

                playerCommanderAvatars.put(playerId, commanderId);
                logger.info("Replaced avatar for " + player.getName() +
                        " with commander art: " + commander.getName());

            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warn("Failed to replace avatar for " + player.getName(), e);
            }
        }
    }

    /**
     * Crop the art portion from a full MTG card image.
     * Uses conservative percentages that work for both old (pre-8th) and modern frames.
     * Returns a square crop from the center of the art region.
     */
    private static BufferedImage cropCardArt(BufferedImage cardImage) {
        int cardW = cardImage.getWidth();
        int cardH = cardImage.getHeight();

        // Conservative art box that works for old and new frames
        int artX = (int) (cardW * 0.08);
        int artY = (int) (cardH * 0.12);
        int artW = (int) (cardW * 0.84);
        int artH = (int) (cardH * 0.37);

        // Clamp to image bounds
        artX = Math.max(0, Math.min(artX, cardW - 1));
        artY = Math.max(0, Math.min(artY, cardH - 1));
        artW = Math.min(artW, cardW - artX);
        artH = Math.min(artH, cardH - artY);

        // Extract a centered square from the art box
        int squareSize = Math.min(artW, artH);
        int squareX = artX + (artW - squareSize) / 2;
        int squareY = artY + (artH - squareSize) / 2;

        if (squareSize <= 0) {
            squareSize = Math.min(cardW, cardH) / 2;
            squareX = (cardW - squareSize) / 2;
            squareY = (cardH - squareSize) / 2;
        }

        return cardImage.getSubimage(squareX, squareY, squareSize, squareSize);
    }

    /**
     * Update player panel visibility for streaming mode.
     * Hides redundant elements and shows counters conditionally.
     */
    private void updatePlayerPanelVisibility(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();

        for (PlayerView player : game.getPlayers()) {
            PlayAreaPanel playArea = players.get(player.getPlayerId());
            if (playArea == null) {
                continue;
            }

            PlayerPanelExt playerPanel = playArea.getPlayerPanel();
            cleanupPlayerPanel(playerPanel, player);

            // Show cost label for chatterbox players
            updateCostLabel(player, playerPanel);
        }
    }

    /**
     * Clean up a player panel for streaming mode.
     * Hides redundant elements, keeps only: avatar + library count
     * Shows poison/energy/experience/rad only when > 0
     */
    private void cleanupPlayerPanel(PlayerPanelExt playerPanel, PlayerView player) {
        try {
            // Hide mana pool (all 6 colors)
            setFieldsVisible(playerPanel, false, "manaLabels", "manaButtons");

            // Hide life counter (redundant - shown on avatar)
            setComponentVisible(playerPanel, "life", false);
            setComponentVisible(playerPanel, "lifeLabel", false);

            // Hide hand/graveyard/exile counts (redundant - shown inline)
            setComponentVisible(playerPanel, "hand", false);
            setComponentVisible(playerPanel, "handLabel", false);
            setComponentVisible(playerPanel, "grave", false);
            setComponentVisible(playerPanel, "graveLabel", false);
            setComponentVisible(playerPanel, "exileZone", false);
            setComponentVisible(playerPanel, "exileLabel", false);

            // Hide zones panel (command zone, cheat, hints - observers can't use)
            setComponentVisible(playerPanel, "zonesPanel", false);

            // Conditional counters - show only when label value > 0
            // (label text is already set by parent's update before we're called)
            setCounterVisibleIfNonZero(playerPanel, "poison", "poisonLabel");
            setCounterVisibleIfNonZero(playerPanel, "energy", "energyLabel");
            setCounterVisibleIfNonZero(playerPanel, "experience", "experienceLabel");
            setCounterVisibleIfNonZero(playerPanel, "rad", "radLabel");

            // Resize the panel to be shorter since we've hidden many elements
            resizePlayerPanel(playerPanel);

        } catch (Exception e) {
            logger.warn("Failed to cleanup player panel via reflection", e);
        }
    }

    /**
     * Compute the avatar size for streaming mode, scaling to the window.
     * At 1080p this gives ~98px (close to the original 80px).
     * At 4K this gives ~196px, making avatars clearly visible.
     */
    private int computeAvatarSize(Component component) {
        // Use the window height if available, fall back to screen height
        Window window = SwingUtilities.getWindowAncestor(component);
        int windowHeight = window != null && window.getHeight() > 0
                ? window.getHeight()
                : Toolkit.getDefaultToolkit().getScreenSize().height;
        // Scale: window_height / 11 gives good proportions at all resolutions
        int avatarSize = windowHeight / 11;
        return Math.max(80, Math.min(avatarSize, 300));
    }

    /**
     * Resize the player panel after hiding elements, scaling to the window size.
     */
    private void resizePlayerPanel(PlayerPanelExt playerPanel) {
        try {
            int avatarSize = computeAvatarSize(playerPanel);
            int panelWidth = avatarSize + 14;  // avatar + GroupLayout padding
            int panelHeight = avatarSize + 40; // avatar + name/library count

            // Get the panelBackground which contains the actual content
            Field bgField = PlayerPanelExt.class.getDeclaredField("panelBackground");
            bgField.setAccessible(true);
            JComponent panelBackground = (JComponent) bgField.get(playerPanel);

            if (panelBackground != null) {
                Dimension newSize = new Dimension(panelWidth, panelHeight);
                panelBackground.setPreferredSize(newSize);
                panelBackground.setMaximumSize(newSize);
                panelBackground.revalidate();
            }

            // Also resize the player panel itself
            Dimension newSize = new Dimension(panelWidth, panelHeight + 5);
            playerPanel.setPreferredSize(newSize);
            playerPanel.setMaximumSize(newSize);
            playerPanel.revalidate();

        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to resize player panel", e);
        }
    }

    /**
     * Show counter icon and label only if the label value is > 0.
     */
    private void setCounterVisibleIfNonZero(PlayerPanelExt playerPanel, String iconField, String labelField) {
        try {
            Field labelF = PlayerPanelExt.class.getDeclaredField(labelField);
            labelF.setAccessible(true);
            JLabel label = (JLabel) labelF.get(playerPanel);

            boolean visible = false;
            if (label != null) {
                String text = label.getText();
                if (text != null && !text.isEmpty()) {
                    try {
                        visible = Integer.parseInt(text) > 0;
                    } catch (NumberFormatException e) {
                        // Keep hidden if not a number
                    }
                }
            }

            setComponentVisible(playerPanel, iconField, visible);
            if (label != null) {
                label.setVisible(visible);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Field may not exist, ignore
        }
    }

    /**
     * Set visibility on a single component field.
     */
    private void setComponentVisible(PlayerPanelExt playerPanel, String fieldName, boolean visible) {
        try {
            Field field = PlayerPanelExt.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Component component = (Component) field.get(playerPanel);
            if (component != null) {
                component.setVisible(visible);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Field may not exist in all versions, ignore
        }
    }

    /**
     * Set visibility on map-based fields (manaLabels, manaButtons).
     */
    private void setFieldsVisible(PlayerPanelExt playerPanel, boolean visible, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = PlayerPanelExt.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(playerPanel);
                if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    for (Object key : map.keySet()) {
                        if (key instanceof Component) {
                            ((Component) key).setVisible(visible);
                        }
                    }
                    for (Object val : map.values()) {
                        if (val instanceof Component) {
                            ((Component) val).setVisible(visible);
                        }
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Field may not exist, ignore
            }
        }
    }

    // ---- LLM cost display ----

    /**
     * Initialize cost file polling for chatterbox (LLM) players.
     * Reads the game directory and player config from system properties/environment,
     * then starts a Swing timer to poll cost files every 2 seconds.
     */
    private void initCostPolling() {
        if (costPollingInitialized) {
            return;
        }
        costPollingInitialized = true;

        String gameDirStr = System.getProperty("xmage.streaming.gameDir");
        if (gameDirStr == null || gameDirStr.isEmpty()) {
            return;
        }
        gameDirPath = Paths.get(gameDirStr);

        // Parse players config to find chatterbox player names
        String configJson = System.getenv("XMAGE_AI_HARNESS_PLAYERS_CONFIG");
        if (configJson != null && !configJson.isEmpty()) {
            parseChatterboxPlayers(configJson);
        }

        if (chatterboxPlayerNames.isEmpty()) {
            return;
        }

        logger.info("Cost polling enabled for chatterbox players: " + chatterboxPlayerNames);

        // Poll cost files every 2 seconds
        costPollTimer = new Timer(2000, e -> pollCostFiles());
        costPollTimer.start();
    }

    /**
     * Initialize the game event JSONL writer if game directory is configured.
     */
    private void initGameEventLog() {
        if (gameEventWriter != null) {
            return;
        }
        // gameDirPath may not be set yet if initCostPolling bailed early
        if (gameDirPath == null) {
            String gameDirStr = System.getProperty("xmage.streaming.gameDir");
            if (gameDirStr != null && !gameDirStr.isEmpty()) {
                gameDirPath = Paths.get(gameDirStr);
            }
        }
        if (gameDirPath == null) {
            return;
        }
        try {
            gameEventWriter = new PrintWriter(new FileWriter(gameDirPath.resolve("game_events.jsonl").toString(), true));
        } catch (IOException e) {
            logger.warn("Failed to open game_events.jsonl", e);
        }
    }

    /**
     * Write a single JSONL event line to game_events.jsonl.
     */
    private void writeGameEvent(String type, JsonObject data) {
        if (gameEventWriter == null) {
            return;
        }
        gameEventSeq++;
        data.addProperty("ts", ZonedDateTime.now(LOG_TZ).format(LOG_TS_FMT));
        data.addProperty("seq", gameEventSeq);
        data.addProperty("type", type);
        gameEventWriter.println(data.toString());
        gameEventWriter.flush();
    }

    /**
     * Write a state_snapshot event if the game state has meaningfully changed
     * (turn, phase, step, or any player's life/battlefield/hand changed).
     */
    private void writeStateSnapshotIfChanged(GameView game) {
        if (gameEventWriter == null || game == null) {
            return;
        }
        // Skip snapshots until hand permissions are granted (avoids incomplete early snapshots)
        Map<String, SimpleCardsView> watchedHands = game.getWatchedHands();
        if (watchedHands == null || watchedHands.isEmpty()) {
            return;
        }
        // Build a compact key for deduplication
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(roundTracker.getGameRound()).append("|");
        keyBuilder.append(game.getPhase()).append("|");
        keyBuilder.append(game.getStep()).append("|");
        for (PlayerView p : game.getPlayers()) {
            keyBuilder.append(p.getName()).append(":").append(p.getLife()).append(":")
                      .append(p.getHandCount()).append(":")
                      .append(p.getBattlefield() != null ? p.getBattlefield().size() : 0).append(",");
        }
        String key = keyBuilder.toString();
        if (key.equals(lastSnapshotKey)) {
            return;
        }
        lastSnapshotKey = key;

        JsonObject event = new JsonObject();
        event.addProperty("turn", roundTracker.getGameRound());
        event.addProperty("phase", game.getPhase() != null ? game.getPhase().name() : "");
        event.addProperty("step", game.getStep() != null ? game.getStep().name() : "");
        event.addProperty("active_player", safe(game.getActivePlayerName()));
        event.addProperty("priority_player", safe(game.getPriorityPlayerName()));

        // Build compact player state (without layout info)
        JsonArray playersArray = new JsonArray();
        Map<String, Card> loadedCards = getLoadedCards();
        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("name", safe(player.getName()));
            playerJson.addProperty("life", player.getLife());
            playerJson.addProperty("library_count", player.getLibraryCount());
            playerJson.addProperty("hand_count", player.getHandCount());
            playerJson.addProperty("is_active", player.isActive());
            playerJson.addProperty("has_left", player.hasLeft());
            playerJson.add("counters", countersToJson(player));

            // Battlefield - compact (name + tapped only)
            JsonArray bfArray = new JsonArray();
            if (player.getBattlefield() != null) {
                for (PermanentView perm : player.getBattlefield().values()) {
                    JsonObject permJson = new JsonObject();
                    permJson.addProperty("name", safe(perm.getDisplayName()));
                    permJson.addProperty("tapped", perm.isTapped());
                    if (perm.isCreature()) {
                        permJson.addProperty("power", safe(perm.getPower()));
                        permJson.addProperty("toughness", safe(perm.getToughness()));
                    }
                    bfArray.add(permJson);
                }
            }
            playerJson.add("battlefield", bfArray);

            // Commanders
            JsonArray cmdArray = new JsonArray();
            if (player.getCommandObjectList() != null) {
                for (CommandObjectView cmd : player.getCommandObjectList()) {
                    cmdArray.add(safe(cmd.getName()));
                }
            }
            playerJson.add("commanders", cmdArray);

            // Graveyard (names only)
            JsonArray gyArray = new JsonArray();
            if (player.getGraveyard() != null) {
                for (CardView card : player.getGraveyard().values()) {
                    gyArray.add(safe(card.getDisplayName()));
                }
            }
            playerJson.add("graveyard", gyArray);

            // Hand cards (observer has permission to see all hands)
            CardsView handCards = getHandCardsForPlayer(player, game, loadedCards);
            JsonArray handArray = new JsonArray();
            if (handCards != null) {
                for (CardView card : handCards.values()) {
                    JsonObject cardJson = new JsonObject();
                    cardJson.addProperty("name", safe(card.getDisplayName()));
                    cardJson.addProperty("mana_cost", safe(card.getManaCostStr()));
                    handArray.add(cardJson);
                }
            }
            playerJson.add("hand", handArray);

            playersArray.add(playerJson);
        }
        event.add("players", playersArray);

        // Stack
        JsonArray stackArray = new JsonArray();
        if (game.getStack() != null) {
            for (CardView card : game.getStack().values()) {
                JsonObject stackJson = new JsonObject();
                stackJson.addProperty("name", safe(card.getDisplayName()));
                stackArray.add(stackJson);
            }
        }
        event.add("stack", stackArray);

        writeGameEvent("state_snapshot", event);
    }

    /**
     * Log a game event from the chat panel (game action or player chat).
     */
    void logChatEvent(String type, String message, String username) {
        JsonObject event = new JsonObject();
        if ("player_chat".equals(type)) {
            event.addProperty("from", username != null ? username : "");
        }
        event.addProperty("message", message != null ? message : "");
        writeGameEvent(type, event);
    }

    /**
     * Parse the players config JSON to extract chatterbox player names.
     */
    private void parseChatterboxPlayers(String configJson) {
        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            if (root.has("players")) {
                for (com.google.gson.JsonElement elem : root.getAsJsonArray("players")) {
                    JsonObject player = elem.getAsJsonObject();
                    String type = player.has("type") ? player.get("type").getAsString() : "";
                    if ("chatterbox".equals(type) || "pilot".equals(type)) {
                        chatterboxPlayerNames.add(player.get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse chatterbox players from config", e);
        }
    }

    /**
     * Poll cost JSON files written by chatterbox processes.
     */
    private void pollCostFiles() {
        if (gameDirPath == null) {
            return;
        }
        for (String username : chatterboxPlayerNames) {
            Path costFile = gameDirPath.resolve(username + "_cost.json");
            try {
                if (Files.exists(costFile)) {
                    String content = new String(Files.readAllBytes(costFile));
                    JsonObject data = JsonParser.parseString(content).getAsJsonObject();
                    double cost = data.get("cost_usd").getAsDouble();
                    playerCosts.put(username, cost);
                }
            } catch (Exception e) {
                // File may be mid-write, ignore and retry next poll
            }
        }
    }

    /**
     * Update or create the cost label for a player if they are a chatterbox.
     */
    private void updateCostLabel(PlayerView player, PlayerPanelExt playerPanel) {
        String playerName = player.getName();
        if (!chatterboxPlayerNames.contains(playerName)) {
            return;
        }

        Double cost = playerCosts.get(playerName);
        if (cost == null) {
            return;
        }

        UUID playerId = player.getPlayerId();
        JLabel costLabel = costLabels.get(playerId);

        if (costLabel == null) {
            // Create and inject cost label into the west panel
            costLabel = new JLabel();
            costLabel.setHorizontalAlignment(SwingConstants.CENTER);
            costLabel.setForeground(new Color(0, 200, 0));
            costLabel.setFont(costLabel.getFont().deriveFont(Font.BOLD, 11f));
            costLabel.setPreferredSize(new Dimension(94, 16));
            costLabel.setMaximumSize(new Dimension(94, 16));

            Container westPanel = playerPanel.getParent();
            if (westPanel instanceof JPanel) {
                // Insert after playerPanel (index 1), before commander panel
                westPanel.add(costLabel, 1);
                westPanel.revalidate();
                westPanel.repaint();
                costLabels.put(playerId, costLabel);
            }
        }

        costLabel.setText(formatCost(cost));
        costLabel.setVisible(true);
    }

    /**
     * Format a USD cost value for display.
     */
    private static String formatCost(double costUsd) {
        return String.format("$%.4f", costUsd);
    }

    // ---- Overlay state publishing ----

    private void pushOverlayState(GameView game, boolean force) {
        LocalOverlayServer overlayServer = LocalOverlayServer.getInstance();
        if (!overlayServer.isRunning() || game == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastOverlayPushMs < OVERLAY_PUSH_INTERVAL_MS) {
            return;
        }
        lastOverlayPushMs = now;

        try {
            overlayServer.updateState(buildOverlayStateJson(game));
        } catch (Exception e) {
            logger.debug("Failed to publish overlay state", e);
        }
    }

    private String buildOverlayStateJson(GameView game) {
        OverlayLayoutSnapshot layout = buildOverlayLayoutSnapshot(game);

        JsonObject root = new JsonObject();
        root.addProperty("status", "live");
        root.addProperty("updatedAt", ZonedDateTime.now(LOG_TZ).format(LOG_TS_FMT));
        root.addProperty("gameId", streamingGameId != null ? streamingGameId.toString() : "");
        root.addProperty("turn", roundTracker.getGameRound());
        root.addProperty("phase", game.getPhase() != null ? game.getPhase().name() : "");
        root.addProperty("step", game.getStep() != null ? game.getStep().name() : "");
        root.addProperty("activePlayer", safe(game.getActivePlayerName()));
        root.addProperty("priorityPlayer", safe(game.getPriorityPlayerName()));
        root.add("players", buildOverlayPlayers(game, layout));
        root.add("stack", cardsToJson(game.getStack(), "stack", null, layout));
        root.add("layout", buildOverlayLayoutJson(layout));
        return root.toString();
    }

    private JsonArray buildOverlayPlayers(GameView game, OverlayLayoutSnapshot layout) {
        JsonArray playersArray = new JsonArray();
        Map<String, Card> loadedCards = getLoadedCards();

        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("id", playerId.toString());
            playerJson.addProperty("name", safe(player.getName()));
            playerJson.addProperty("life", player.getLife());
            playerJson.addProperty("libraryCount", player.getLibraryCount());
            playerJson.addProperty("handCount", player.getHandCount());
            playerJson.addProperty("isActive", player.isActive());
            playerJson.addProperty("hasLeft", player.hasLeft());
            playerJson.addProperty("timerActive", player.isTimerActive());
            playerJson.addProperty("priorityTimeLeftSecs", player.getPriorityTimeLeftSecs());
            playerJson.addProperty("bufferTimeLeft", player.getBufferTimeLeft());
            playerJson.add("counters", countersToJson(player));
            playerJson.add("battlefield", battlefieldToJson(player, layout));
            playerJson.add("commanders", commandersToJson(player, layout));
            playerJson.add("graveyard", cardsToJson(player.getGraveyard(), "graveyard", playerId, layout));
            playerJson.add("exile", cardsToJson(player.getExile(), "exile", playerId, layout));

            CardsView handCards = getHandCardsForPlayer(player, game, loadedCards);
            playerJson.add("hand", cardsToJson(handCards, "hand", playerId, layout));

            playersArray.add(playerJson);
        }

        return playersArray;
    }

    private JsonArray countersToJson(PlayerView player) {
        JsonArray counters = new JsonArray();
        for (CounterView counter : player.getCounters()) {
            JsonObject counterJson = new JsonObject();
            counterJson.addProperty("name", safe(counter.getName()));
            counterJson.addProperty("count", counter.getCount());
            counters.add(counterJson);
        }
        return counters;
    }

    private JsonArray battlefieldToJson(PlayerView player, OverlayLayoutSnapshot layout) {
        JsonArray cards = new JsonArray();
        List<PermanentView> permanents = new ArrayList<>(player.getBattlefield().values());
        permanents.sort(Comparator.comparing(card -> safe(card.getDisplayName()).toLowerCase(Locale.ROOT)));
        for (PermanentView permanent : permanents) {
            cards.add(cardToJson(permanent, "battlefield", player.getPlayerId(), layout));
        }
        return cards;
    }

    private JsonArray commandersToJson(PlayerView player, OverlayLayoutSnapshot layout) {
        JsonArray commanders = new JsonArray();
        for (CommandObjectView obj : player.getCommandObjectList()) {
            if (obj instanceof CommanderView) {
                commanders.add(cardToJson((CommanderView) obj, "commanders", player.getPlayerId(), layout));
            }
        }
        return commanders;
    }

    private JsonArray cardsToJson(CardsView cardsView, String zone, UUID playerId, OverlayLayoutSnapshot layout) {
        JsonArray cards = new JsonArray();
        if (cardsView == null || cardsView.isEmpty()) {
            return cards;
        }

        List<CardView> sorted = new ArrayList<>(cardsView.values());
        sorted.sort(Comparator.comparing(card -> safe(card.getDisplayName()).toLowerCase(Locale.ROOT)));
        for (CardView card : sorted) {
            cards.add(cardToJson(card, zone, playerId, layout));
        }
        return cards;
    }

    private JsonObject cardToJson(CardView card, String zone, UUID playerId, OverlayLayoutSnapshot layout) {
        JsonObject cardJson = new JsonObject();
        String displayName = safe(card.getDisplayName());
        String cardName = displayName.isEmpty() ? safe(card.getName()) : displayName;

        String cardId = card.getId() != null ? card.getId().toString() : "";
        cardJson.addProperty("id", cardId);
        cardJson.addProperty("name", cardName);
        cardJson.addProperty("displayFullName", safe(card.getDisplayFullName()));
        cardJson.addProperty("manaCost", safe(card.getManaCostStr()));
        cardJson.addProperty("typeLine", formatTypeLine(card));
        cardJson.addProperty("rules", formatRules(card.getRules()));
        cardJson.addProperty("power", safe(card.getPower()));
        cardJson.addProperty("toughness", safe(card.getToughness()));
        cardJson.addProperty("loyalty", safe(card.getLoyalty()));
        cardJson.addProperty("defense", safe(card.getDefense()));
        cardJson.addProperty("expansionSetCode", safe(card.getExpansionSetCode()));
        cardJson.addProperty("cardNumber", safe(card.getCardNumber()));
        cardJson.addProperty("imageUrl", buildCardImageUrl(card));
        cardJson.addProperty("tapped", card instanceof PermanentView && ((PermanentView) card).isTapped());
        cardJson.addProperty("damage", card instanceof PermanentView ? ((PermanentView) card).getDamage() : 0);
        if (!cardId.isEmpty()) {
            Rectangle rect = layout.cardRectsByKey.get(layoutCardKey(playerId, zone, card.getId()));
            if (rect != null) {
                cardJson.add("layout", rectangleToJson(rect));
            }
        }

        return cardJson;
    }

    private OverlayLayoutSnapshot buildOverlayLayoutSnapshot(GameView game) {
        OverlayLayoutSnapshot snapshot = new OverlayLayoutSnapshot();
        snapshot.sourceWidth = Math.max(1, getWidth());
        snapshot.sourceHeight = Math.max(1, getHeight());

        if (game == null || game.getPlayers() == null) {
            return snapshot;
        }

        Map<UUID, PlayAreaPanel> playAreas = getPlayers();
        for (PlayerView player : game.getPlayers()) {
            UUID playerId = player.getPlayerId();
            PlayAreaPanel playArea = playAreas.get(playerId);
            if (playArea == null) {
                continue;
            }

            Rectangle playAreaRect = toOverlayRect(playArea);
            if (playAreaRect != null) {
                snapshot.playAreaRects.put(playerId, playAreaRect);
            }

            addCardRectsFromMap(
                    snapshot,
                    playerId,
                    "battlefield",
                    playArea.getBattlefieldPanel().getPermanentPanels()
            );
            addCardRectsFromHandPanel(snapshot, playerId, playArea.getHandPanel());

            // Use streaming zone panels (with public getCardPanels) instead of reflection
            StreamingGraveyardPanel gyPanel = streamingGraveyardPanels.get(playerId);
            if (gyPanel != null) {
                addCardRectsFromMap(snapshot, playerId, "graveyard", gyPanel.getCardPanels());
            }
            StreamingExilePanel exPanel = streamingExilePanels.get(playerId);
            if (exPanel != null) {
                addCardRectsFromMap(snapshot, playerId, "exile", exPanel.getCardPanels());
            }
            CommanderPanel commanderPanel = commanderPanels.get(playerId);
            if (commanderPanel != null) {
                addCardRectsFromMap(snapshot, playerId, "commanders", commanderPanel.getCardPanels());
            }
        }

        // Stack cards (global, not per-player)
        addStackCardRects(snapshot);

        return snapshot;
    }

    private void addCardRectsFromHandPanel(OverlayLayoutSnapshot snapshot, UUID playerId, HandPanel handPanel) {
        if (handPanel == null) {
            return;
        }
        try {
            Field handField = HandPanel.class.getDeclaredField("hand");
            handField.setAccessible(true);
            Cards handCards = (Cards) handField.get(handPanel);
            addCardRectsFromMap(snapshot, playerId, "hand", handCards.getMageCardsForUpdate());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Best effort only; if this fails, non-positional overlay still works.
        }
    }



    private void addStackCardRects(OverlayLayoutSnapshot snapshot) {
        try {
            Field stackField = GamePanel.class.getDeclaredField("stackObjects");
            stackField.setAccessible(true);
            Cards stackCards = (Cards) stackField.get(this);
            addCardRectsFromMap(snapshot, null, "stack", stackCards.getMageCardsForUpdate());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Best effort only
        }
    }

    private void addCardRectsFromMap(
            OverlayLayoutSnapshot snapshot,
            UUID playerId,
            String zone,
            Map<UUID, MageCard> cardsMap
    ) {
        if (cardsMap == null || cardsMap.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, MageCard> entry : cardsMap.entrySet()) {
            UUID cardId = entry.getKey();
            MageCard card = entry.getValue();
            if (cardId == null || card == null) {
                continue;
            }
            Rectangle rect = toOverlayRect(card);
            if (rect == null) {
                continue;
            }
            snapshot.cardRectsByKey.put(layoutCardKey(playerId, zone, cardId), rect);
        }
    }

    private Rectangle toOverlayRect(Component component) {
        if (component == null || component.getParent() == null || !component.isVisible()) {
            return null;
        }
        try {
            // MageCard.getBounds() is deprecated and returns oversized Swing-allocated
            // bounds. Use getCardLocationOnScreen() which returns the actual visual
            // card rectangle (accounting for outer/draw space), then convert from
            // screen coordinates to this panel's coordinate space.
            if (component instanceof MageCard) {
                MageCard card = (MageCard) component;
                MageCardLocation cardLoc = card.getCardLocationOnScreen();
                Point panelOrigin = this.getLocationOnScreen();
                Rectangle rect = new Rectangle(
                        cardLoc.getCardX() - panelOrigin.x,
                        cardLoc.getCardY() - panelOrigin.y,
                        cardLoc.getCardWidth(),
                        cardLoc.getCardHeight()
                );
                if (rect.width <= 0 || rect.height <= 0) {
                    return null;
                }
                return rect;
            }

            Rectangle rect = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), this);
            if (rect.width <= 0 || rect.height <= 0) {
                return null;
            }
            return rect;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject buildOverlayLayoutJson(OverlayLayoutSnapshot layout) {
        JsonObject layoutJson = new JsonObject();
        layoutJson.addProperty("sourceWidth", layout.sourceWidth);
        layoutJson.addProperty("sourceHeight", layout.sourceHeight);

        JsonArray playAreas = new JsonArray();
        for (Map.Entry<UUID, Rectangle> entry : layout.playAreaRects.entrySet()) {
            JsonObject area = rectangleToJson(entry.getValue());
            area.addProperty("playerId", entry.getKey().toString());
            playAreas.add(area);
        }
        layoutJson.add("playAreas", playAreas);
        return layoutJson;
    }

    private static JsonObject rectangleToJson(Rectangle rect) {
        JsonObject json = new JsonObject();
        json.addProperty("x", rect.x);
        json.addProperty("y", rect.y);
        json.addProperty("width", rect.width);
        json.addProperty("height", rect.height);
        return json;
    }

    private static String layoutCardKey(UUID playerId, String zone, UUID cardId) {
        String player = playerId == null ? "global" : playerId.toString();
        String z = zone == null ? "unknown" : zone;
        return player + "|" + z + "|" + cardId;
    }

    private static final class OverlayLayoutSnapshot {
        private int sourceWidth;
        private int sourceHeight;
        private final Map<String, Rectangle> cardRectsByKey = new LinkedHashMap<>();
        private final Map<UUID, Rectangle> playAreaRects = new LinkedHashMap<>();
    }

    private static String formatRules(List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        return String.join("\n", rules);
    }

    private static String formatTypeLine(CardView card) {
        StringBuilder sb = new StringBuilder();

        if (card.getSuperTypes() != null && !card.getSuperTypes().isEmpty()) {
            for (Object superType : card.getSuperTypes()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(superType.toString());
            }
        }

        if (card.getCardTypes() != null && !card.getCardTypes().isEmpty()) {
            for (Object cardType : card.getCardTypes()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(cardType.toString());
            }
        }

        String subTypes = card.getSubTypes() == null ? "" : card.getSubTypes().toString();
        if (!subTypes.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append(subTypes);
        }

        return sb.toString();
    }

    private static String buildCardImageUrl(CardView card) {
        String setCode = safe(card.getExpansionSetCode());
        String cardNumber = safe(card.getCardNumber());
        if (!setCode.isEmpty() && !cardNumber.isEmpty()) {
            return "https://api.scryfall.com/cards/"
                    + encodeUrlComponent(setCode.toLowerCase(Locale.ROOT))
                    + "/"
                    + encodeUrlComponent(cardNumber)
                    + "?format=image&version=normal";
        }

        String cardName = safe(card.getName());
        if (!cardName.isEmpty()) {
            return "https://api.scryfall.com/cards/named?exact="
                    + encodeUrlComponent(cardName)
                    + "&format=image&version=normal";
        }

        return "";
    }

    private static String encodeUrlComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Start recording the game panel to a video file.
     *
     * @param outputPath Path to the output video file (.mov)
     */
    public void startRecording(Path outputPath) {
        if (frameCaptureService != null && frameCaptureService.isRunning()) {
            logger.warn("Recording already in progress");
            return;
        }

        this.recordingPath = outputPath;
        FFmpegEncoder encoder = new FFmpegEncoder(outputPath);
        frameCaptureService = new FrameCaptureService(this, 30, encoder);
        frameCaptureService.start();

        // Add shutdown hook to ensure recording is finalized on Ctrl+C
        shutdownHook = new Thread(() -> {
            logger.info("Shutdown hook: stopping recording");
            if (frameCaptureService != null) {
                frameCaptureService.stop();
            }
        }, "RecordingShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Stop recording if in progress.
     */
    public void stopRecording() {
        // Remove shutdown hook first to avoid double-stop
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down, hook will run anyway
            }
            shutdownHook = null;
        }

        if (frameCaptureService != null) {
            frameCaptureService.stop();
            frameCaptureService = null;
            logger.info("Recording stopped: " + recordingPath);
        }
    }

    /**
     * Check if recording is currently active.
     */
    public boolean isRecording() {
        return frameCaptureService != null && frameCaptureService.isRunning();
    }
}
