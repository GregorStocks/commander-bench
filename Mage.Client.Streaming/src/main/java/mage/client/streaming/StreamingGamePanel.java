package mage.client.streaming;

import mage.cards.Card;
import mage.client.MagePane;
import mage.client.SessionHandler;
import mage.client.dialog.PreferencesDialog;
import mage.client.game.GamePanel;
import mage.client.game.PlayAreaPanel;
import mage.client.game.PlayAreaPanelOptions;
import mage.client.util.CardsViewUtil;
import mage.constants.PlayerAction;
import mage.view.CardsView;
import mage.view.GameView;
import mage.view.PlayerView;
import mage.view.SimpleCardsView;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.HashSet;
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

    @Override
    public synchronized void watchGame(UUID currentTableId, UUID parentTableId, UUID gameId, MagePane gamePane) {
        this.streamingGameId = gameId;
        super.watchGame(currentTableId, parentTableId, gameId, gamePane);
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
    }

    /**
     * Override to enable showHandInPlayArea for all players in streaming mode.
     */
    @Override
    protected PlayAreaPanelOptions createPlayAreaPanelOptions(GameView game, PlayerView player, boolean playerItself, boolean topRow) {
        return new PlayAreaPanelOptions(
                game.isPlayer(),
                player.isHuman(),
                playerItself,
                game.isRollbackTurnsAllowed(),
                topRow,
                true  // showHandInPlayArea enabled for streaming
        );
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
            Field bigCardPanelField = GamePanel.class.getDeclaredField("bigCardPanel");
            bigCardPanelField.setAccessible(true);
            JPanel bigCardPanel = (JPanel) bigCardPanelField.get(this);
            if (bigCardPanel != null) {
                bigCardPanel.setVisible(false);
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
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.warn("Failed to remove hand/stack splitter from restore", e);
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
     * Distribute hand cards to each player's PlayAreaPanel.
     */
    private void distributeHands(GameView game) {
        if (game == null || game.getPlayers() == null) {
            return;
        }

        Map<UUID, PlayAreaPanel> players = getPlayers();
        Map<String, Card> loadedCards = getLoadedCards();

        for (PlayerView player : game.getPlayers()) {
            PlayAreaPanel playArea = players.get(player.getPlayerId());
            if (playArea == null) {
                continue;
            }

            // Get hand cards for this player from watched hands
            CardsView handToShow = getHandCardsForPlayer(player, game, loadedCards);
            playArea.loadHandCards(handToShow, getBigCard(), getGameId());
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
}
