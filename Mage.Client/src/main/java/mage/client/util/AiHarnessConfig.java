package mage.client.util;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import mage.players.PlayerType;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for AI harness games, allowing specification of player types.
 *
 * Example config file (.context/ai-harness-config.json):
 * {
 *   "players": [
 *     {"type": "bot", "ai": "COMPUTER_MAD", "name": "Bot 1"},
 *     {"type": "bot", "ai": "COMPUTER_MAD", "name": "Bot 2"},
 *     {"type": "bot", "ai": "COMPUTER_MAD", "name": "Bot 3"},
 *     {"type": "skeleton", "name": "skeleton-1"}
 *   ]
 * }
 */
public class AiHarnessConfig {

    private static final Logger LOGGER = Logger.getLogger(AiHarnessConfig.class);
    private static final String[] CONFIG_PATHS = {
        ".context/ai-harness-config.json",           // If running from workspace root
        "../.context/ai-harness-config.json",        // If running from Mage.Client/
        "Mage.Client/.context/ai-harness-config.json" // Legacy path
    };

    private List<PlayerConfig> players = new ArrayList<>();

    public List<PlayerConfig> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerConfig> players) {
        this.players = players;
    }

    public int getBotCount() {
        return (int) players.stream().filter(p -> "bot".equals(p.type)).count();
    }

    public int getSkeletonCount() {
        return (int) players.stream().filter(p -> "skeleton".equals(p.type)).count();
    }

    public List<PlayerConfig> getBots() {
        List<PlayerConfig> bots = new ArrayList<>();
        for (PlayerConfig p : players) {
            if ("bot".equals(p.type)) {
                bots.add(p);
            }
        }
        return bots;
    }

    public List<PlayerConfig> getSkeletons() {
        List<PlayerConfig> skeletons = new ArrayList<>();
        for (PlayerConfig p : players) {
            if ("skeleton".equals(p.type)) {
                skeletons.add(p);
            }
        }
        return skeletons;
    }

    public static class PlayerConfig {
        public String type; // "bot" or "skeleton"
        public String ai;   // for bots: "COMPUTER_MAD", "COMPUTER_MONTE_CARLO"
        public String name;

        public PlayerType getPlayerType() {
            if ("skeleton".equals(type)) {
                return PlayerType.HUMAN;
            }
            if (ai == null || ai.isEmpty()) {
                return PlayerType.COMPUTER_MAD;
            }
            try {
                return PlayerType.valueOf(ai);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown AI type: " + ai + ", defaulting to COMPUTER_MAD");
                return PlayerType.COMPUTER_MAD;
            }
        }
    }

    private static final String PLAYERS_CONFIG_ENV = "XMAGE_AI_HARNESS_PLAYERS_CONFIG";

    /**
     * Load config from environment variable, file, or return a default config with 4 bots.
     */
    public static AiHarnessConfig load() {
        // First, try to load from environment variable (passed by puppeteer)
        String configJson = System.getenv(PLAYERS_CONFIG_ENV);
        if (configJson != null && !configJson.isEmpty()) {
            try {
                Gson gson = new Gson();
                AiHarnessConfig config = gson.fromJson(configJson, AiHarnessConfig.class);
                if (config != null && config.players != null && !config.players.isEmpty()) {
                    LOGGER.info("Loaded AI harness config from environment variable with " +
                            config.getBotCount() + " bots and " + config.getSkeletonCount() + " skeletons");
                    return config;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to parse AI harness config from environment variable", e);
            }
        }

        // Fall back to file-based loading
        for (String path : CONFIG_PATHS) {
            File configFile = new File(path);
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    Gson gson = new Gson();
                    AiHarnessConfig config = gson.fromJson(reader, AiHarnessConfig.class);
                    if (config != null && config.players != null && !config.players.isEmpty()) {
                        LOGGER.info("Loaded AI harness config from " + configFile.getAbsolutePath() +
                                " with " + config.getBotCount() + " bots and " + config.getSkeletonCount() + " skeletons");
                        return config;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load AI harness config from " + configFile.getAbsolutePath(), e);
                }
            }
        }

        // Default: 4 bots
        LOGGER.info("Using default AI harness config (4 bots) - no config file found at any of: " + String.join(", ", CONFIG_PATHS));
        return createDefault();
    }

    private static AiHarnessConfig createDefault() {
        AiHarnessConfig config = new AiHarnessConfig();
        for (int i = 1; i <= 4; i++) {
            PlayerConfig player = new PlayerConfig();
            player.type = "bot";
            player.ai = "COMPUTER_MAD";
            player.name = "Computer " + i;
            config.players.add(player);
        }
        return config;
    }
}
