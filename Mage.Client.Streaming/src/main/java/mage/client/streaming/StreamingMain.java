package mage.client.streaming;

import mage.client.MageFrame;
import mage.client.dialog.PreferencesDialog;
import mage.client.util.EDTExceptionHandler;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.Charset;

/**
 * Entry point for the streaming-optimized XMage client.
 *
 * This creates a StreamingMageFrame instead of a regular MageFrame,
 * which uses StreamingGamePane/StreamingGamePanel for watching games.
 * The streaming panel automatically requests hand permission from all players.
 *
 * Usage:
 *   java -jar mage-client-streaming.jar [standard XMage client args]
 *
 * Or via Maven:
 *   mvn exec:java -pl Mage.Client.Streaming
 */
public class StreamingMain {

    private static final Logger LOGGER = Logger.getLogger(StreamingMain.class);

    public static void main(final String[] args) {
        // Same setup as MageFrame.main()
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        LOGGER.info("Starting MAGE STREAMING CLIENT");
        LOGGER.info("Java version: " + System.getProperty("java.version"));
        LOGGER.info("Logging level: " + LOGGER.getEffectiveLevel());
        LOGGER.info("Default charset: " + Charset.defaultCharset());

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOGGER.fatal(null, e));

        SwingUtilities.invokeLater(() -> {
            // Parse command line args
            boolean liteMode = false;
            for (String arg : args) {
                if (arg.startsWith("-lite")) {
                    liteMode = true;
                }
            }

            // Show splash unless in lite mode
            if (!liteMode) {
                final SplashScreen splash = SplashScreen.getSplashScreen();
                if (splash != null) {
                    Graphics2D g2 = splash.createGraphics();
                    try {
                        g2.setComposite(AlphaComposite.Clear);
                        g2.fillRect(120, 140, 200, 40);
                        g2.setPaintMode();
                        g2.setColor(Color.white);
                        g2.drawString("Streaming Mode", 560, 460);
                    } finally {
                        g2.dispose();
                    }
                    splash.update();
                }
            }

            // Auto-update settings if needed (same as MageFrame)
            int settingsVersion = PreferencesDialog.getCachedValue(PreferencesDialog.KEY_SETTINGS_VERSION, 0);
            if (settingsVersion == 0) {
                LOGGER.info("Settings: first run, applying GUI size settings");
                int screenDPI = Toolkit.getDefaultToolkit().getScreenResolution();
                int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;
                String preset = PreferencesDialog.getDefaultSizeSettings().findBestPreset(screenDPI, screenHeight);
                if (preset != null) {
                    LOGGER.info("Settings: selected preset " + preset);
                    PreferencesDialog.getDefaultSizeSettings().applyPreset(preset);
                }
                PreferencesDialog.saveValue(PreferencesDialog.KEY_SETTINGS_VERSION, String.valueOf(1));
            }

            // Create the streaming frame (instead of regular MageFrame)
            try {
                StreamingMageFrame streamingFrame = new StreamingMageFrame();
                StreamingMageFrame.setInstance(streamingFrame);
                EDTExceptionHandler.registerMainApp(streamingFrame);
                streamingFrame.setVisible(true);
                LOGGER.info("Streaming client started successfully");
            } catch (Throwable e) {
                LOGGER.fatal("Critical error on start up: " + e.getMessage(), e);
                System.exit(1);
            }
        });
    }
}
