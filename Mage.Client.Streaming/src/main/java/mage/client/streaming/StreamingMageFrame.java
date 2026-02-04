package mage.client.streaming;

import mage.MageException;
import mage.client.MageFrame;
import mage.client.MagePane;
import mage.client.game.GamePane;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Streaming-optimized MageFrame that uses StreamingGamePane for watching games.
 * Skips the lobby UI and supports auto-watching a table via command-line args.
 */
public class StreamingMageFrame extends MageFrame {

    private static final Logger LOGGER = Logger.getLogger(StreamingMageFrame.class);
    private static final String GIT_BRANCH = getGitBranch();
    private static final String STREAMING_TITLE_PREFIX = "[STREAMING] " +
            (GIT_BRANCH != null ? "[" + GIT_BRANCH + "] " : "");

    /**
     * Get the current git branch name, or null if not in a git repo.
     */
    private static String getGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String branch = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && branch != null && !branch.isEmpty()) {
                    return branch.trim();
                }
            }
        } catch (Exception e) {
            // Not in a git repo or git not available - that's fine
        }
        return null;
    }

    public StreamingMageFrame() throws MageException {
        super();
        // Hide toolbar after initialization
        SwingUtilities.invokeLater(this::hideToolbar);
    }

    /**
     * Hide the main application toolbar since streaming observers don't need it.
     */
    private void hideToolbar() {
        try {
            Field toolbarField = MageFrame.class.getDeclaredField("mageToolbar");
            toolbarField.setAccessible(true);
            JToolBar toolbar = (JToolBar) toolbarField.get(this);
            if (toolbar != null) {
                toolbar.setVisible(false);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Log but don't fail - toolbar visibility is not critical
            System.err.println("Failed to hide toolbar: " + e.getMessage());
        }
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
     * Override to initialize lobby (for AI harness game creation) but keep it hidden.
     * The parent method initializes TablesPane which handles auto-start in AI harness mode.
     */
    @Override
    public void prepareAndShowServerLobby() {
        // Call parent to initialize TablesPane (needed for AI harness game creation)
        super.prepareAndShowServerLobby();

        // Then immediately hide the lobby
        LOGGER.info("Streaming mode: hiding lobby UI");
        hideServerLobby();
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
