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
 * Player types:
 * - "cpu" or "bot": Computer player (uses AI field, defaults to COMPUTER_MAD)
 * - "sleepwalker": Headless client with MCP control (creates HUMAN slot)
 * - "chatterbox": LLM-powered headless client with MCP control (creates HUMAN slot)
 * - "pilot": LLM-powered strategic game player with MCP control (creates HUMAN slot)
 * - "potato": Headless client with auto-response (creates HUMAN slot)
 * - "staller": Headless client with slow auto-response (creates HUMAN slot)
 * - "skeleton": Legacy headless client (creates HUMAN slot)
 *
 * Example config file (.context/ai-harness-config.json):
 * {
 *   "players": [
 *     {"type": "sleepwalker", "name": "Sleepy"},
 *     {"type": "potato", "name": "Spud"},
 *     {"type": "cpu", "name": "Mad AI 1"},
 *     {"type": "cpu", "name": "Mad AI 2"}
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
    private String gameType;   // e.g. "Two Player Duel", "Commander Free For All"
    private String deckType;   // e.g. "Constructed - Legacy", "Variant Magic - Freeform Commander"

    public List<PlayerConfig> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerConfig> players) {
        this.players = players;
    }

    public String getGameType() {
        return gameType;
    }

    public String getDeckType() {
        return deckType;
    }

    public int getBotCount() {
        return (int) players.stream().filter(p -> p.isBot()).count();
    }

    public int getSkeletonCount() {
        return (int) players.stream().filter(p -> p.isHeadless()).count();
    }

    public List<PlayerConfig> getBots() {
        List<PlayerConfig> bots = new ArrayList<>();
        for (PlayerConfig p : players) {
            if (p.isBot()) {
                bots.add(p);
            }
        }
        return bots;
    }

    public List<PlayerConfig> getSkeletons() {
        List<PlayerConfig> skeletons = new ArrayList<>();
        for (PlayerConfig p : players) {
            if (p.isHeadless()) {
                skeletons.add(p);
            }
        }
        return skeletons;
    }

    public static class PlayerConfig {
        public String type; // "cpu"/"bot", "sleepwalker", "pilot", "potato", "staller", "skeleton"
        public String ai;   // for bots: "COMPUTER_MAD", "COMPUTER_MONTE_CARLO"
        public String name;
        public String deck; // optional path to .dck file (relative to project root)

        /**
         * Returns true if this is a CPU/bot player (server-controlled AI).
         */
        public boolean isBot() {
            return "bot".equals(type) || "cpu".equals(type);
        }

        /**
         * Returns true if this is a headless client player (needs HUMAN slot).
         */
        public boolean isHeadless() {
            return "skeleton".equals(type) || "sleepwalker".equals(type) || "potato".equals(type) || "staller".equals(type) || "chatterbox".equals(type) || "pilot".equals(type);
        }

        public PlayerType getPlayerType() {
            if (isHeadless()) {
                return PlayerType.HUMAN;
            }
            if (!isBot()) {
                throw new IllegalArgumentException("Unknown player type: \"" + type + "\". " +
                        "Valid types: cpu, bot, sleepwalker, chatterbox, pilot, potato, staller, skeleton");
            }
            // Bot/CPU player
            if (ai == null || ai.isEmpty()) {
                return PlayerType.COMPUTER_MAD;
            }
            try {
                return PlayerType.valueOf(ai);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown AI type: \"" + ai + "\". " +
                        "Valid types: COMPUTER_MAD, COMPUTER_MONTE_CARLO");
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
                    // Debug: log each player's type
                    for (PlayerConfig p : config.players) {
                        LOGGER.info("  Player: " + p.name + ", type=" + p.type + ", isBot=" + p.isBot() + ", isHeadless=" + p.isHeadless());
                    }
                    LOGGER.info("Loaded AI harness config from environment variable with " +
                            config.getBotCount() + " CPU players and " + config.getSkeletonCount() + " headless players");
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
                                " with " + config.getBotCount() + " CPU players and " + config.getSkeletonCount() + " headless players");
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
