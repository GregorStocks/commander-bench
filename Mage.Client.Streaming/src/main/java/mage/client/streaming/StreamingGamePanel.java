package mage.client.streaming;

import mage.abilities.icon.CardIconRenderSettings;
import mage.cards.Card;
import mage.cards.MageCard;
import mage.client.MagePane;
import mage.client.SessionHandler;
import mage.client.cards.Cards;
import mage.client.chat.ChatPanelBasic;
import mage.client.dialog.PreferencesDialog;
import mage.client.game.ExilePanel;
import mage.client.game.GamePanel;
import mage.client.game.HandPanel;
import mage.client.game.PlayAreaPanel;
import mage.client.game.PlayAreaPanelOptions;
import mage.client.game.PlayerPanelExt;
import mage.client.plugins.adapters.MageActionCallback;
import mage.client.plugins.impl.Plugins;
import mage.client.util.CardsViewUtil;
import mage.client.util.GUISizeHelper;
import mage.constants.PlayerAction;
import mage.constants.Zone;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.CommanderView;
import mage.view.CommandObjectView;
import mage.view.GameView;
import mage.view.PlayerView;
import mage.view.SimpleCardsView;
import org.apache.log4j.Logger;

import mage.client.streaming.recording.FrameCaptureService;
import mage.client.streaming.recording.FFmpegEncoder;

import javax.swing.*;
import java.awt.*;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    @Override
    public synchronized void watchGame(UUID currentTableId, UUID parentTableId, UUID gameId, MagePane gamePane) {
        this.streamingGameId = gameId;
        replaceChatWithCombinedPanel();  // Replace before super connects chat
        super.watchGame(currentTableId, parentTableId, gameId, gamePane);
    }

    /**
     * Replace the separate game chat and user chat panels with a single combined panel.
     * This must be called BEFORE super.watchGame() which connects the chat to the server.
     */
    private void replaceChatWithCombinedPanel() {
        if (chatPanelReplaced) {
            return;
        }

        try {
            combinedChatPanel = new CombinedChatPanel();

            // Access fields via reflection (matching existing pattern in this class)
            Field gameChatField = GamePanel.class.getDeclaredField("gameChatPanel");
            gameChatField.setAccessible(true);
            Field userChatField = GamePanel.class.getDeclaredField("userChatPanel");
            userChatField.setAccessible(true);

            // Replace both chat panel references with our combined panel
            // (no connectedChat = all messages go to this single panel)
            gameChatField.set(this, combinedChatPanel);
            userChatField.set(this, combinedChatPanel);

            // Replace the entire splitChatAndLogs with just our combined panel
            // in the parent splitBattlefieldAndChats
            Field splitBattlefieldField = GamePanel.class.getDeclaredField("splitBattlefieldAndChats");
            splitBattlefieldField.setAccessible(true);
            JSplitPane splitBattlefield = (JSplitPane) splitBattlefieldField.get(this);
            if (splitBattlefield != null) {
                splitBattlefield.setRightComponent(combinedChatPanel);
            }

            chatPanelReplaced = true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to setup combined chat panel", e);
        }
    }

    @Override
    public synchronized void init(int messageId, GameView game, boolean callGameUpdateAfterInit) {
        super.init(messageId, game, callGameUpdateAfterInit);
        this.lastGame = game;
        // Hide the central hand container (we show hands in play areas instead)
        hideHandContainer();
        requestHandPermissions(game);
    }

    @Override
    public synchronized void updateGame(int messageId, GameView game) {
        super.updateGame(messageId, game);
        this.lastGame = game;
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
        // Clean up player panels (hide redundant elements)
        updatePlayerPanelVisibility(game);
    }

    /**
     * Override to auto-close the streaming observer after the game ends.
     * Waits 10 seconds then exits, which triggers recording finalization via shutdown hook.
     */
    @Override
    public void endMessage(int messageId, GameView gameView, Map<String, Serializable> options, String message) {
        super.endMessage(messageId, gameView, options, message);

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
