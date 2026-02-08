package mage.client.headless;

import mage.cards.decks.DeckCardLists;
import mage.constants.TableState;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.players.net.UserSkipPrioritySteps;
import mage.remote.Connection;
import mage.remote.MageRemoteException;
import mage.remote.Session;
import mage.remote.SessionImpl;
import mage.view.SeatView;
import mage.view.TableView;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.Enumeration;
import java.util.UUID;

/**
 * Main entry point for the headless XMage client.
 *
 * This client connects to an XMage server, joins the first available table
 * with an open human slot, and responds to all game callbacks automatically.
 *
 * Supports two personalities:
 * - potato (default): Auto-responds to all callbacks (passes priority, picks first option)
 * - sleepwalker: Exposes MCP server on stdio for external client control
 *
 * Usage:
 *   java -jar mage-client-headless.jar --server localhost --port 17171 --username bot1
 *   java -jar mage-client-headless.jar --personality sleepwalker --server localhost --port 17171
 *
 * Or via system properties:
 *   -Dxmage.headless.server=localhost
 *   -Dxmage.headless.port=17171
 *   -Dxmage.headless.username=bot1
 *   -Dxmage.headless.password=
 *   -Dxmage.headless.personality=potato
 */
public class HeadlessClient {

    private static final Logger logger = Logger.getLogger(HeadlessClient.class);
    private static final int TABLE_POLL_INTERVAL_MS = 1000;
    private static final int TABLE_POLL_TIMEOUT_MS = 60000;
    private static final int PING_INTERVAL_MS = 20000; // 20 seconds, same as normal client

    private static final String PERSONALITY_POTATO = "potato";
    private static final String PERSONALITY_SLEEPWALKER = "sleepwalker";

    public static void main(String[] args) {
        String server = getArg(args, "--server", System.getProperty("xmage.headless.server", "localhost"));
        int port = getIntArg(args, "--port", Integer.getInteger("xmage.headless.port", 17171));
        String username = getArg(args, "--username", System.getProperty("xmage.headless.username", "skeleton-" + System.currentTimeMillis()));
        String password = getArg(args, "--password", System.getProperty("xmage.headless.password", ""));
        String personality = getArg(args, "--personality", System.getProperty("xmage.headless.personality", PERSONALITY_POTATO));

        boolean isSleepwalker = PERSONALITY_SLEEPWALKER.equalsIgnoreCase(personality);

        // In sleepwalker mode, redirect all log4j output to stderr since stdout is used for MCP
        if (isSleepwalker) {
            redirectLogsToStderr();
            logger.info("Starting in SLEEPWALKER mode (MCP server on stdio)");
        }

        // Log class file timestamp to verify build freshness
        try {
            java.net.URL classUrl = HeadlessClient.class.getResource("HeadlessClient.class");
            if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                long mtime = new java.io.File(classUrl.toURI()).lastModified();
                logger.info("Build: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(mtime)));
            }
        } catch (Exception ignored) {}

        logger.info("Starting headless client: " + username + "@" + server + ":" + port + " [" + personality + "]");

        SkeletonMageClient client = new SkeletonMageClient(username);
        Session session = new SessionImpl(client);
        client.setSession(session);

        // Get callback handler and configure MCP mode
        SkeletonCallbackHandler callbackHandler = client.getCallbackHandler();
        if (isSleepwalker) {
            callbackHandler.setMcpMode(true);
        }

        Connection connection = new Connection();
        connection.setHost(server);
        connection.setPort(port);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setProxyType(Connection.ProxyType.NONE);

        // Set user data with allowRequestShowHandCards=true so observers can see hands
        UserData userData = new UserData(
                UserGroup.PLAYER,
                0, // avatarId
                true, // allowRequestShowHandCards - important for streaming observers
                true, // confirmEmptyManaPool
                new UserSkipPrioritySteps(),
                "world", // flagName
                false, // askMoveToGraveOrder
                true, // manaPoolAutomatic
                true, // manaPoolAutomaticRestricted
                false, // passPriorityCast
                false, // passPriorityActivation
                true, // autoOrderTrigger
                1, // autoTargetLevel
                true, // useSameSettingsForReplacementEffects
                false, // useFirstManaAbility
                "" // userIdStr
        );
        connection.setUserData(userData);

        logger.info("Connecting to server...");
        if (!session.connectStart(connection)) {
            logger.error("Failed to connect: " + session.getLastError());
            System.exit(1);
        }

        logger.info("Connected! Looking for tables to join...");

        // Try to join a table and start the match
        UUID roomId = session.getMainRoomId();
        if (roomId == null) {
            logger.error("Failed to get main room ID");
            session.connectStop(false, false);
            System.exit(1);
        }

        UUID tableId = tryJoinTable(session, roomId, username);
        if (tableId == null) {
            logger.error("Failed to join any table within timeout");
            session.connectStop(false, false);
            System.exit(1);
        }

        logger.info("Joined table, waiting for game to start (table creator will start match)...");

        if (isSleepwalker) {
            // Start MCP server on stdio - this blocks until client stops
            logger.info("Starting MCP server...");
            McpServer mcpServer = new McpServer(callbackHandler);

            // Run MCP server in separate thread so we can monitor client state
            Thread mcpThread = new Thread(() -> mcpServer.start(), "MCP-Server");
            mcpThread.setDaemon(true);
            mcpThread.start();

            // Keep alive while client is running
            long lastPingTime = System.currentTimeMillis();
            while (client.isRunning()) {
                try {
                    Thread.sleep(1000);

                    // Ping server periodically to maintain session
                    long now = System.currentTimeMillis();
                    if (now - lastPingTime >= PING_INTERVAL_MS) {
                        session.ping();
                        lastPingTime = now;
                    }
                } catch (InterruptedException e) {
                    logger.info("Interrupted, stopping...");
                    client.stop();
                    mcpServer.stop();
                    break;
                }
            }
            mcpServer.stop();
        } else {
            // Potato mode: just keep alive while client is running
            long lastPingTime = System.currentTimeMillis();
            while (client.isRunning()) {
                try {
                    Thread.sleep(1000);

                    // Ping server periodically to maintain session
                    long now = System.currentTimeMillis();
                    if (now - lastPingTime >= PING_INTERVAL_MS) {
                        session.ping();
                        lastPingTime = now;
                    }
                } catch (InterruptedException e) {
                    logger.info("Interrupted, stopping...");
                    client.stop();
                    break;
                }
            }
        }

        logger.info("Disconnecting...");
        session.connectStop(false, false);
        logger.info("Done.");
    }

    private static UUID tryJoinTable(Session session, UUID roomId, String username) {
        String deckPath = System.getProperty("xmage.headless.deck");
        DeckCardLists deck = loadDeck(deckPath);
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < TABLE_POLL_TIMEOUT_MS) {
            try {
                Collection<TableView> tables = session.getTables(roomId);
                if (tables != null) {
                    for (TableView table : tables) {
                        if (table.getTableState() == TableState.WAITING) {
                            // Check for empty human seats
                            for (SeatView seat : table.getSeats()) {
                                if (seat.getPlayerType() == PlayerType.HUMAN &&
                                    (seat.getPlayerId() == null || seat.getPlayerName().isEmpty())) {
                                    logger.info("Found table with open seat: " + table.getTableId() + " (" + table.getTableName() + ")");
                                    if (session.joinTable(roomId, table.getTableId(), username, PlayerType.HUMAN, 1, deck, "")) {
                                        logger.info("Successfully joined table " + table.getTableId());
                                        return table.getTableId();
                                    } else {
                                        logger.warn("Failed to join table " + table.getTableId() + ", trying another...");
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (MageRemoteException e) {
                logger.warn("Error getting tables: " + e.getMessage());
            }

            try {
                Thread.sleep(TABLE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

        return null;
    }

    private static boolean waitAndStartMatch(Session session, UUID roomId, UUID tableId) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < TABLE_POLL_TIMEOUT_MS) {
            try {
                Collection<TableView> tables = session.getTables(roomId);
                if (tables != null) {
                    for (TableView table : tables) {
                        if (table.getTableId().equals(tableId)) {
                            TableState state = table.getTableState();
                            if (state == TableState.READY_TO_START) {
                                logger.info("Table is ready, starting match...");
                                if (session.startMatch(roomId, tableId)) {
                                    logger.info("Match started successfully");
                                    return true;
                                } else {
                                    logger.warn("Failed to start match, will retry...");
                                }
                            } else if (state == TableState.STARTING || state == TableState.DUELING) {
                                logger.info("Match already starting/started");
                                return true;
                            } else {
                                logger.debug("Table state: " + state + ", waiting...");
                            }
                            break;
                        }
                    }
                }
            } catch (MageRemoteException e) {
                logger.warn("Error getting tables: " + e.getMessage());
            }

            try {
                Thread.sleep(TABLE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

        return false;
    }

    private static DeckCardLists loadDeck(String deckPath) {
        if (deckPath == null || deckPath.isEmpty()) {
            logger.info("No deck path specified, using test deck");
            return createTestDeck();
        }

        java.io.File deckFile = new java.io.File(deckPath);
        if (!deckFile.exists()) {
            logger.warn("Deck file not found: " + deckPath + ", using test deck");
            return createTestDeck();
        }

        try {
            // Parse deck file directly without needing CardRepository
            // Format: "count [SET:number] Card Name" or "SB: count [SET:number] Card Name"
            DeckCardLists deck = new DeckCardLists();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^(SB:\\s*)?(\\d+)\\s+\\[([^:]+):(\\d+)\\]\\s+(.+)$"
            );

            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(deckFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                        continue;
                    }

                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    if (matcher.matches()) {
                        boolean isSideboard = matcher.group(1) != null;
                        int count = Integer.parseInt(matcher.group(2));
                        String setCode = matcher.group(3);
                        String cardNumber = matcher.group(4);
                        String cardName = matcher.group(5).trim();

                        mage.cards.decks.DeckCardInfo cardInfo = new mage.cards.decks.DeckCardInfo(
                            cardName, cardNumber, setCode, count
                        );

                        if (isSideboard) {
                            deck.getSideboard().add(cardInfo);
                        } else {
                            deck.getCards().add(cardInfo);
                        }
                    }
                }
            }

            if (deck.getCards().isEmpty() && deck.getSideboard().isEmpty()) {
                logger.warn("Deck is empty after parsing: " + deckPath + ", using test deck");
                return createTestDeck();
            }

            logger.info("Loaded deck from " + deckPath + " with " +
                    deck.getCards().size() + " main deck cards and " +
                    deck.getSideboard().size() + " sideboard cards");
            return deck;
        } catch (Exception e) {
            logger.warn("Failed to load deck from " + deckPath + ", using test deck", e);
            return createTestDeck();
        }
    }

    private static DeckCardLists createTestDeck() {
        // Simple test deck for Freeform Commander (99 cards + 1 commander)
        DeckCardLists deck = new DeckCardLists();
        // Add basic lands - 99 total for the main deck
        deck.getCards().add(new mage.cards.decks.DeckCardInfo("Swamp", "1", "SLD", 20));
        deck.getCards().add(new mage.cards.decks.DeckCardInfo("Forest", "1", "SLD", 20));
        deck.getCards().add(new mage.cards.decks.DeckCardInfo("Island", "1", "SLD", 20));
        deck.getCards().add(new mage.cards.decks.DeckCardInfo("Mountain", "1", "SLD", 20));
        deck.getCards().add(new mage.cards.decks.DeckCardInfo("Plains", "1", "SLD", 19));
        // Add a legendary creature as commander (Child of Alara - 5-color, from Conflux)
        deck.getSideboard().add(new mage.cards.decks.DeckCardInfo("Child of Alara", "72", "CON", 1));
        return deck;
    }

    private static String getArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int getIntArg(String[] args, String name, int defaultValue) {
        String value = getArg(args, name, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid integer for " + name + ": " + value + ", using default: " + defaultValue);
            }
        }
        return defaultValue;
    }

    private static void redirectLogsToStderr() {
        // Redirect all ConsoleAppender instances to use stderr instead of stdout
        Logger rootLogger = Logger.getRootLogger();
        Enumeration<?> appenders = rootLogger.getAllAppenders();
        while (appenders.hasMoreElements()) {
            Object appender = appenders.nextElement();
            if (appender instanceof ConsoleAppender) {
                ((ConsoleAppender) appender).setTarget("System.err");
                ((ConsoleAppender) appender).activateOptions();
            }
        }
    }
}
