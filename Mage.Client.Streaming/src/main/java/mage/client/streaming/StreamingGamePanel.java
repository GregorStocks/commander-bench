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
import java.io.Serializable;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
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

    // Commander panels injected into each player's play area
    private final Map<UUID, CommanderPanel> commanderPanels = new HashMap<>();
    private boolean commanderPanelsInjected = false;

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
        // Hide the central hand container (we show hands in play areas instead)
        hideHandContainer();
        requestHandPermissions(game);
        initCostPolling();
        // Schedule auto-dismissal of any popup dialogs created during init
        schedulePopupDismissal();
        pushOverlayState(game, true);
    }

    @Override
    public synchronized void updateGame(int messageId, GameView game) {
        super.updateGame(messageId, game);
        restoreDeadPlayerPanelSizes();
        this.lastGame = game;
        // Schedule auto-dismissal of any popup dialogs created by the parent
        schedulePopupDismissal();
        // Hide the central hand container (we show hands in play areas instead)
        hideHandContainer();
        // Also try to request permissions on updates in case we missed init
        requestHandPermissions(game);
        // Distribute hands to each player's PlayAreaPanel
        distributeHands(game);
        // Distribute graveyards to each player's PlayAreaPanel
        distributeGraveyards(game);
        // Distribute exile to each player's PlayAreaPanel
        distributeExile(game);
        // Inject and distribute commanders to each player's PlayAreaPanel
        injectCommanderPanels(game);
        distributeCommanders(game);
        // Replace default avatars with commander card art
        replaceAvatarsWithCommanderArt(game);
        // Clean up player panels (hide redundant elements)
        updatePlayerPanelVisibility(game);
        pushOverlayState(game, false);
    }

    /**
     * Override to auto-close the streaming observer after the game ends.
     * Waits 10 seconds then exits, which triggers recording finalization via shutdown hook.
     */
    @Override
    public void endMessage(int messageId, GameView gameView, Map<String, Serializable> options, String message) {
        super.endMessage(messageId, gameView, options, message);
        pushOverlayState(gameView, true);

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
                    helperArea.revalidate();
                    helperArea.repaint();
                }
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
     * Distribute graveyard cards to each player's PlayAreaPanel.
     */
    private void distributeGraveyards(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();

        for (PlayerView player : game.getPlayers()) {
            PlayAreaPanel playArea = players.get(player.getPlayerId());
            if (playArea == null) {
                continue;
            }

            // Get graveyard cards for this player (always available, no permissions needed)
            CardsView graveyardCards = player.getGraveyard();
            playArea.loadGraveyardCards(graveyardCards, getBigCard(), getGameId());
        }
    }

    /**
     * Distribute exile cards to each player's PlayAreaPanel.
     * PlayerView.getExile() already filters cards by ownership.
     */
    private void distributeExile(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();

        for (PlayerView player : game.getPlayers()) {
            PlayAreaPanel playArea = players.get(player.getPlayerId());
            if (playArea == null) {
                logger.debug("No play area for player: " + player.getName());
                continue;
            }

            // Get exile cards for this player (filtered by ownership in PlayerView)
            CardsView exileCards = player.getExile();
            if (exileCards != null && !exileCards.isEmpty()) {
                logger.info("Player " + player.getName() + " has " + exileCards.size() + " exiled cards");
            }

            ExilePanel exilePanel = playArea.getExilePanel();
            if (exilePanel == null) {
                logger.warn("No exile panel for player: " + player.getName());
            }

            playArea.loadExileCards(exileCards, getBigCard(), getGameId());
        }
    }

    /**
     * Inject CommanderPanel into each player's west panel via reflection.
     * This is called once after play areas are created.
     */
    private void injectCommanderPanels(GameView game) {
        if (commanderPanelsInjected || game == null || game.getPlayers() == null) {
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

                // Create commander panel
                CommanderPanel commanderPanel = new CommanderPanel();
                commanderPanels.put(player.getPlayerId(), commanderPanel);

                // Add commander panel after player panel (index 1)
                // Layout: playerPanel (0), commanderPanel (1), graveyardPanel (2), exilePanel (3)
                westPanel.add(commanderPanel, 1);
                westPanel.revalidate();
                westPanel.repaint();

                logger.info("Injected commander panel for player: " + player.getName());
            } catch (Exception e) {
                logger.warn("Failed to inject commander panel for player: " + player.getName(), e);
            }
        }

        commanderPanelsInjected = true;
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

            // Crop the art region and resize for avatar
            BufferedImage artCrop = cropCardArt(cardImage);
            Rectangle avatarRect = new Rectangle(80, 80);
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
     * Resize the player panel to be shorter after hiding elements.
     */
    private void resizePlayerPanel(PlayerPanelExt playerPanel) {
        try {
            // Get the panelBackground which contains the actual content
            Field bgField = PlayerPanelExt.class.getDeclaredField("panelBackground");
            bgField.setAccessible(true);
            JComponent panelBackground = (JComponent) bgField.get(playerPanel);

            if (panelBackground != null) {
                // Reduce height - keep only avatar + name + library count
                // Original is ~270px, target ~120px (avatar 80 + name 30 + padding)
                int width = panelBackground.getPreferredSize().width;
                Dimension newSize = new Dimension(width, 120);
                panelBackground.setPreferredSize(newSize);
                panelBackground.setMaximumSize(newSize);
                panelBackground.revalidate();
            }

            // Also resize the player panel itself
            int width = playerPanel.getPreferredSize().width;
            Dimension newSize = new Dimension(width, 125);
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
        root.addProperty("updatedAt", Instant.now().toString());
        root.addProperty("gameId", streamingGameId != null ? streamingGameId.toString() : "");
        root.addProperty("turn", game.getTurn());
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
            addCardRectsFromPanelField(snapshot, playerId, "graveyard", playArea.getGraveyardPanel());
            addCardRectsFromPanelField(snapshot, playerId, "exile", playArea.getExilePanel());

            CommanderPanel commanderPanel = commanderPanels.get(playerId);
            if (commanderPanel != null) {
                addCardRectsFromMap(snapshot, playerId, "commanders", commanderPanel.getCardPanels());
            }
        }

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

    private void addCardRectsFromPanelField(OverlayLayoutSnapshot snapshot, UUID playerId, String zone, JPanel panel) {
        if (panel == null) {
            return;
        }
        if (!(panel instanceof GraveyardPanel || panel instanceof ExilePanel)) {
            return;
        }
        try {
            Field cardsField = panel.getClass().getDeclaredField("cards");
            cardsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, MageCard> cardMap = (Map<UUID, MageCard>) cardsField.get(panel);
            addCardRectsFromMap(snapshot, playerId, zone, cardMap);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Best effort only; if this fails, non-positional overlay still works.
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
