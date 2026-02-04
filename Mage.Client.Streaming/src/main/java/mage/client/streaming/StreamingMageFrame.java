package mage.client.streaming;

import mage.MageException;
import mage.client.MageFrame;
import mage.client.MagePane;
import mage.client.game.GamePane;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Streaming-optimized MageFrame that uses StreamingGamePane for watching games.
 */
public class StreamingMageFrame extends MageFrame {

    private static final String STREAMING_TITLE_PREFIX = "[STREAMING] ";

    public StreamingMageFrame() throws MageException {
        super();
    }

    /**
     * Override setTitle to always add streaming prefix.
     * This intercepts all title changes from MageFrame.setWindowTitle().
     */
    @Override
    public void setTitle(String title) {
        if (title != null && !title.startsWith(STREAMING_TITLE_PREFIX)) {
            super.setTitle(STREAMING_TITLE_PREFIX + title);
        } else {
            super.setTitle(title);
        }
    }

    /**
     * Override watchGame to use StreamingGamePane instead of GamePane.
     */
    @Override
    public void watchGame(UUID currentTableId, UUID parentTableId, UUID gameId) {
        // Check if we're already watching this game
        for (Component component : getDesktop().getComponents()) {
            if (component instanceof StreamingGamePane
                    && ((StreamingGamePane) component).getGameId().equals(gameId)) {
                setActive((MagePane) component);
                return;
            }
            // Also check for regular GamePane in case it was created elsewhere
            if (component instanceof GamePane
                    && ((GamePane) component).getGameId().equals(gameId)) {
                setActive((MagePane) component);
                return;
            }
        }

        // Create streaming game pane
        StreamingGamePane gamePane = new StreamingGamePane();
        getDesktop().add(gamePane, JLayeredPane.DEFAULT_LAYER);
        gamePane.setVisible(true);
        gamePane.watchGame(currentTableId, parentTableId, gameId);
        setActive(gamePane);
    }

    /**
     * Set this instance as the MageFrame singleton using reflection.
     * This is necessary because MageFrame.instance is private.
     */
    public static void setInstance(MageFrame frame) {
        try {
            Field instanceField = MageFrame.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, frame);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to set MageFrame instance via reflection", e);
        }
    }
}
