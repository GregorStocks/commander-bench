package mage.client.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP (Model Context Protocol) server using stdio transport.
 * Implements JSON-RPC 2.0 over newline-delimited stdin/stdout.
 *
 */
public class McpServer {

    private static final Logger logger = Logger.getLogger(McpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "xmage-bridge";
    private static final String SERVER_VERSION = "1.0.0";

    private final BridgeCallbackHandler callbackHandler;
    private final Gson gson;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PrintWriter stdout;
    private boolean initialized = false;

    public McpServer(BridgeCallbackHandler callbackHandler) {
        this.callbackHandler = callbackHandler;
        this.gson = new GsonBuilder().create();
        this.stdout = new PrintWriter(System.out, true);
    }

    /** Safely get a string from a JsonObject, returning null if the key is missing or the value is JSON null. */
    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    /**
     * Start the MCP server. Blocks until shutdown.
     */
    public void start() {
        running.set(true);
        logger.info("MCP server starting on stdio");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    handleMessage(line);
                } catch (Exception e) {
                    logger.error("Error handling MCP message", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading from stdin", e);
        }

        logger.info("MCP server stopped");
    }

    public void stop() {
        running.set(false);
    }

    private void handleMessage(String json) {
        JsonObject message = JsonParser.parseString(json).getAsJsonObject();

        String method = message.has("method") ? message.get("method").getAsString() : null;
        JsonElement id = message.has("id") ? message.get("id") : null;
        JsonObject params = message.has("params") ? message.getAsJsonObject("params") : null;

        // Handle notifications (no id)
        if (id == null) {
            handleNotification(method, params);
            return;
        }

        // Handle requests (have id)
        try {
            Object result = handleRequest(method, params);
            sendResponse(id, result, null);
        } catch (Exception e) {
            callbackHandler.logError("MCP request failed (" + method + "): " + e.getMessage());
            sendError(id, -32603, e.getMessage());
        }
    }

    private void handleNotification(String method, JsonObject params) {
        if ("notifications/initialized".equals(method)) {
            logger.info("MCP client sent initialized notification");
            // Client is ready, nothing to do
        } else {
            logger.debug("Unhandled notification: " + method);
        }
    }

    private Object handleRequest(String method, JsonObject params) {
        switch (method) {
            case "initialize":
                return handleInitialize(params);
            case "tools/list":
                return handleToolsList(params);
            case "tools/call":
                return handleToolsCall(params);
            default:
                throw new RuntimeException("Unknown method: " + method);
        }
    }

    private Map<String, Object> handleInitialize(JsonObject params) {
        initialized = true;

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);

        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", new HashMap<>()); // Empty object indicates tools capability
        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);

        logger.info("MCP initialized with protocol version " + PROTOCOL_VERSION);
        return result;
    }

    // -- Helper methods for building output schemas and examples --

    private static Map<String, Object> field(String name, String type, String desc) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", name);
        f.put("type", type);
        f.put("description", desc);
        return f;
    }

    private static Map<String, Object> field(String name, String type, String desc, String conditional) {
        Map<String, Object> f = field(name, type, desc);
        f.put("conditional", conditional);
        return f;
    }

    @SafeVarargs
    private static Map<String, Object> outputSchema(Map<String, Object>... fields) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        for (Map<String, Object> fld : fields) {
            String name = (String) fld.get("name");
            String type = (String) fld.get("type");
            Map<String, Object> prop = new HashMap<>();
            // Convert "array[X]" to proper JSON Schema array type
            if (type.startsWith("array[") && type.endsWith("]")) {
                prop.put("type", "array");
                Map<String, Object> items = new HashMap<>();
                items.put("type", type.substring(6, type.length() - 1));
                prop.put("items", items);
            } else {
                prop.put("type", type);
            }
            if (fld.containsKey("description")) {
                prop.put("description", fld.get("description"));
            }
            properties.put(name, prop);
        }
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> example(String label, String value) {
        Map<String, Object> ex = new HashMap<>();
        ex.put("label", label);
        ex.put("value", value);
        return ex;
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... items) {
        List<T> list = new ArrayList<>();
        for (T item : items) {
            list.add(item);
        }
        return list;
    }

    /**
     * Returns the static list of MCP tool definitions.
     * Can be called without a server instance (used by main() for JSON export).
     */
    public static List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // is_action_on_me
        Map<String, Object> isActionTool = new HashMap<>();
        isActionTool.put("name", "is_action_on_me");
        isActionTool.put("description",
                "Check if game action is currently required. Returns action_pending, and if true: action_type and message.");
        Map<String, Object> isActionSchema = new HashMap<>();
        isActionSchema.put("type", "object");
        isActionSchema.put("properties", new HashMap<>());
        isActionSchema.put("additionalProperties", false);
        isActionTool.put("inputSchema", isActionSchema);
        isActionTool.put("outputSchema", outputSchema(
                field("action_pending", "boolean", "Whether a game action is waiting for your response"),
                field("action_type", "string", "XMage callback method name (e.g. GAME_SELECT, GAME_TARGET)", "action_pending=true"),
                field("message", "string", "Human-readable prompt for the action", "action_pending=true"),
                field("game_over", "boolean", "Whether the game has ended")));
        isActionTool.put("examples", listOf(
                example("Action pending",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"message\": \"Select card to play or pass priority\",\n  \"game_over\": false\n}"),
                example("No action",
                        "{\n  \"action_pending\": false,\n  \"game_over\": false\n}")));
        tools.add(isActionTool);

        // take_action
        Map<String, Object> takeActionTool = new HashMap<>();
        takeActionTool.put("name", "take_action");
        takeActionTool.put("description", "Execute default action (pass priority or first available choice)");
        Map<String, Object> takeActionSchema = new HashMap<>();
        takeActionSchema.put("type", "object");
        takeActionSchema.put("properties", new HashMap<>());
        takeActionSchema.put("additionalProperties", false);
        takeActionTool.put("inputSchema", takeActionSchema);
        takeActionTool.put("outputSchema", outputSchema(
                field("success", "boolean", "Whether the action was executed successfully"),
                field("action_type", "string", "The callback method that was handled", "success=true"),
                field("action_taken", "string", "Description of what was done (e.g. \"passed_priority\")", "success=true"),
                field("error", "string", "Error message", "success=false")));
        takeActionTool.put("examples", listOf(
                example("Success",
                        "{\n  \"success\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"action_taken\": \"passed_priority\"\n}"),
                example("No pending action",
                        "{\n  \"success\": false,\n  \"error\": \"No pending action\"\n}")));
        tools.add(takeActionTool);

        // get_game_log
        Map<String, Object> getLogTool = new HashMap<>();
        getLogTool.put("name", "get_game_log");
        getLogTool.put("description",
                "Get game log text. Returns log, total_length, truncated. Use max_chars for recent entries only.");
        Map<String, Object> getLogSchema = new HashMap<>();
        getLogSchema.put("type", "object");
        Map<String, Object> getLogProps = new HashMap<>();
        Map<String, Object> maxCharsSchema = new HashMap<>();
        maxCharsSchema.put("type", "integer");
        maxCharsSchema.put("description", "Max characters to return (0 or omit for all)");
        getLogProps.put("max_chars", maxCharsSchema);
        Map<String, Object> logCursorSchema = new HashMap<>();
        logCursorSchema.put("type", "integer");
        logCursorSchema.put("description", "Cursor offset from previous get_game_log call. Returns new log text since this offset.");
        getLogProps.put("cursor", logCursorSchema);
        getLogSchema.put("properties", getLogProps);
        getLogSchema.put("additionalProperties", false);
        getLogTool.put("inputSchema", getLogSchema);
        getLogTool.put("outputSchema", outputSchema(
                field("log", "string", "Game log text (may be truncated if max_chars was set)"),
                field("total_length", "integer", "Total length of the full game log in characters"),
                field("truncated", "boolean", "Whether older content was omitted"),
                field("cursor", "integer", "Cursor to pass to the next get_game_log call"),
                field("cursor_reset", "boolean", "Whether requested cursor was too old and had to be reset to oldest retained log offset", "cursor was stale")));
        getLogTool.put("examples", listOf(
                example("Truncated log",
                        "{\n  \"log\": \"Turn 3 - Player1: Mountain entered the battlefield...\"," +
                        "\n  \"total_length\": 5234,\n  \"truncated\": true,\n  \"cursor\": 5234\n}"),
                example("Cursor delta",
                        "{\n  \"log\": \"Player2 casts Swords to Plowshares targeting Goblin Guide.\"," +
                        "\n  \"total_length\": 5301,\n  \"truncated\": false,\n  \"cursor\": 5301\n}")));
        tools.add(getLogTool);

        // send_chat_message
        Map<String, Object> sendChatTool = new HashMap<>();
        sendChatTool.put("name", "send_chat_message");
        sendChatTool.put("description", "Send a chat message to the game");
        Map<String, Object> sendChatSchema = new HashMap<>();
        sendChatSchema.put("type", "object");
        Map<String, Object> sendChatProps = new HashMap<>();
        Map<String, Object> messageSchema = new HashMap<>();
        messageSchema.put("type", "string");
        messageSchema.put("description", "Message to send");
        sendChatProps.put("message", messageSchema);
        sendChatSchema.put("properties", sendChatProps);
        List<String> required = new ArrayList<>();
        required.add("message");
        sendChatSchema.put("required", required);
        sendChatSchema.put("additionalProperties", false);
        sendChatTool.put("inputSchema", sendChatSchema);
        sendChatTool.put("outputSchema", outputSchema(
                field("success", "boolean", "Whether the message was sent")));
        sendChatTool.put("examples", listOf(
                example("Success",
                        "{\n  \"success\": true\n}")));
        tools.add(sendChatTool);

        // wait_for_action
        Map<String, Object> waitActionTool = new HashMap<>();
        waitActionTool.put("name", "wait_for_action");
        waitActionTool.put("description",
                "Block until a game action is required, or timeout. Returns action_pending, action_type, message.");
        Map<String, Object> waitActionSchema = new HashMap<>();
        waitActionSchema.put("type", "object");
        Map<String, Object> waitActionProps = new HashMap<>();
        Map<String, Object> timeoutProp = new HashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Max milliseconds to wait (default 15000)");
        waitActionProps.put("timeout_ms", timeoutProp);
        waitActionSchema.put("properties", waitActionProps);
        waitActionSchema.put("additionalProperties", false);
        waitActionTool.put("inputSchema", waitActionSchema);
        waitActionTool.put("outputSchema", outputSchema(
                field("action_pending", "boolean", "Whether a game action is waiting for your response"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("message", "string", "Human-readable prompt for the action", "action_pending=true"),
                field("game_over", "boolean", "Whether the game has ended")));
        waitActionTool.put("examples", listOf(
                example("Action arrived",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"message\": \"Select card to play or pass priority\",\n  \"game_over\": false\n}"),
                example("Timeout",
                        "{\n  \"action_pending\": false,\n  \"game_over\": false\n}")));
        tools.add(waitActionTool);

        // pass_priority
        Map<String, Object> passPriorityTool = new HashMap<>();
        passPriorityTool.put("name", "pass_priority");
        passPriorityTool.put("description",
                "Auto-pass priority until you need to make a decision: playable cards, combat " +
                "(declare attackers/blockers), or non-priority actions. " +
                "Returns action_pending, action_type, actions_passed, has_playable_cards, combat_phase. " +
                "On timeout: action_pending=false, timeout=true.");
        Map<String, Object> passPrioritySchema = new HashMap<>();
        passPrioritySchema.put("type", "object");
        Map<String, Object> passPriorityProps = new HashMap<>();
        Map<String, Object> passPriorityTimeoutProp = new HashMap<>();
        passPriorityTimeoutProp.put("type", "integer");
        passPriorityTimeoutProp.put("description", "Max milliseconds to wait (default 30000)");
        passPriorityProps.put("timeout_ms", passPriorityTimeoutProp);
        passPrioritySchema.put("properties", passPriorityProps);
        passPrioritySchema.put("additionalProperties", false);
        passPriorityTool.put("inputSchema", passPrioritySchema);
        passPriorityTool.put("outputSchema", outputSchema(
                field("action_pending", "boolean", "Whether a decision-requiring action was found"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("actions_passed", "integer", "Number of priority passes performed"),
                field("has_playable_cards", "boolean", "Whether you have playable cards in hand", "action_pending=true"),
                field("combat_phase", "string", "\"declare_attackers\" or \"declare_blockers\"", "In combat"),
                field("recent_chat", "array[string]", "Chat messages received since last check", "Chat received"),
                field("player_dead", "boolean", "Whether you died during priority passing", "Player died"),
                field("timeout", "boolean", "Whether the operation timed out", "Timeout")));
        passPriorityTool.put("examples", listOf(
                example("Playable cards found",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"actions_passed\": 3,\n  \"has_playable_cards\": true\n}"),
                example("Combat phase",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"actions_passed\": 5,\n  \"has_playable_cards\": false,\n" +
                        "  \"combat_phase\": \"declare_attackers\"\n}"),
                example("Timeout",
                        "{\n  \"action_pending\": false,\n  \"actions_passed\": 12,\n  \"timeout\": true\n}")));
        tools.add(passPriorityTool);

        // wait_and_get_choices
        Map<String, Object> waitAndGetChoicesTool = new HashMap<>();
        waitAndGetChoicesTool.put("name", "wait_and_get_choices");
        waitAndGetChoicesTool.put("description",
                "Block like pass_priority until a decision is needed, then return full get_action_choices output in one call. " +
                "If no action arrives before timeout, returns pass_priority-style timeout payload.");
        Map<String, Object> waitAndGetChoicesSchema = new HashMap<>();
        waitAndGetChoicesSchema.put("type", "object");
        Map<String, Object> waitAndGetChoicesProps = new HashMap<>();
        Map<String, Object> waitAndGetChoicesTimeoutProp = new HashMap<>();
        waitAndGetChoicesTimeoutProp.put("type", "integer");
        waitAndGetChoicesTimeoutProp.put("description", "Max milliseconds to wait (default 30000)");
        waitAndGetChoicesProps.put("timeout_ms", waitAndGetChoicesTimeoutProp);
        waitAndGetChoicesSchema.put("properties", waitAndGetChoicesProps);
        waitAndGetChoicesSchema.put("additionalProperties", false);
        waitAndGetChoicesTool.put("inputSchema", waitAndGetChoicesSchema);
        waitAndGetChoicesTool.put("outputSchema", outputSchema(
                field("action_pending", "boolean", "Whether an action requiring input is pending"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("response_type", "string", "How to respond: select, boolean, index, amount, pile, or multi_amount", "action_pending=true"),
                field("choices", "array[object]", "Available choices when response_type uses indexed selection", "response_type=select/index"),
                field("message", "string", "Prompt text from XMage", "action_pending=true"),
                field("actions_passed", "integer", "Number of priority passes performed before the decision"),
                field("recent_chat", "array[string]", "Chat messages received since last check", "Chat received"),
                field("timeout", "boolean", "Whether the operation timed out", "Timeout")));
        waitAndGetChoicesTool.put("examples", listOf(
                example("Action with choices",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"response_type\": \"select\",\n  \"actions_passed\": 4,\n" +
                        "  \"choices\": [{\"index\": 0, \"description\": \"Lightning Bolt {R} [Cast]\"}]\n}"),
                example("Timeout",
                        "{\n  \"action_pending\": false,\n  \"actions_passed\": 9,\n  \"timeout\": true\n}")));
        tools.add(waitAndGetChoicesTool);

        // auto_pass_until_event
        Map<String, Object> autoPassTool = new HashMap<>();
        autoPassTool.put("name", "auto_pass_until_event");
        autoPassTool.put("description",
                "Auto-handle all actions and block until meaningful game state change. " +
                "Returns event_occurred, new_log, actions_taken.");
        Map<String, Object> autoPassSchema = new HashMap<>();
        autoPassSchema.put("type", "object");
        Map<String, Object> autoPassProps = new HashMap<>();
        Map<String, Object> minCharsProp = new HashMap<>();
        minCharsProp.put("type", "integer");
        minCharsProp.put("description", "Min new log characters to trigger return (default 50)");
        autoPassProps.put("min_new_chars", minCharsProp);
        Map<String, Object> autoPassTimeoutProp = new HashMap<>();
        autoPassTimeoutProp.put("type", "integer");
        autoPassTimeoutProp.put("description", "Max milliseconds to wait (default 10000)");
        autoPassProps.put("timeout_ms", autoPassTimeoutProp);
        autoPassSchema.put("properties", autoPassProps);
        autoPassSchema.put("additionalProperties", false);
        autoPassTool.put("inputSchema", autoPassSchema);
        autoPassTool.put("outputSchema", outputSchema(
                field("event_occurred", "boolean", "Whether a meaningful game state change was detected"),
                field("new_log", "string", "New game log entries since the call started"),
                field("actions_taken", "integer", "Number of actions auto-handled"),
                field("game_over", "boolean", "Whether the game has ended"),
                field("player_dead", "boolean", "Whether you died during auto-passing", "Player died")));
        autoPassTool.put("examples", listOf(
                example("Event occurred",
                        "{\n  \"event_occurred\": true,\n" +
                        "  \"new_log\": \"Player2 casts Lightning Bolt targeting Player1.\\n" +
                        "Player1 loses 3 life.\",\n  \"actions_taken\": 4,\n  \"game_over\": false\n}")));
        tools.add(autoPassTool);

        // get_game_state
        Map<String, Object> gameStateTool = new HashMap<>();
        gameStateTool.put("name", "get_game_state");
        gameStateTool.put("description",
                "Get full game state: turn, phase, players, stack, combat. Each player has life, mana_pool, " +
                "hand (yours only), battlefield (name, tapped, P/T, counters, token/copy/face_down flags), " +
                "graveyard, exile, commanders.");
        Map<String, Object> gameStateSchema = new HashMap<>();
        gameStateSchema.put("type", "object");
        Map<String, Object> gameStateProps = new HashMap<>();
        Map<String, Object> stateCursorProp = new HashMap<>();
        stateCursorProp.put("type", "integer");
        stateCursorProp.put("description", "State cursor from previous get_game_state call. If unchanged, returns a compact payload.");
        gameStateProps.put("cursor", stateCursorProp);
        gameStateSchema.put("properties", gameStateProps);
        gameStateSchema.put("additionalProperties", false);
        gameStateTool.put("inputSchema", gameStateSchema);
        gameStateTool.put("outputSchema", outputSchema(
                field("available", "boolean", "Whether game state is available"),
                field("error", "string", "Error message", "available=false"),
                field("cursor", "integer", "Cursor for the latest known game state"),
                field("unchanged", "boolean", "True when the provided cursor already matches the latest state"),
                field("turn", "integer", "Current turn number"),
                field("phase", "string", "Current phase (e.g. PRECOMBAT_MAIN, COMBAT)"),
                field("step", "string", "Current step within the phase"),
                field("active_player", "string", "Name of the player whose turn it is"),
                field("priority_player", "string", "Name of the player who currently has priority"),
                field("players", "array[object]", "Player objects: name, life, library_size, hand_size, is_active, is_you, hand (yours only), battlefield, graveyard, exile, mana_pool, counters, commanders"),
                field("stack", "array[object]", "Stack objects: name, rules, target_count"),
                field("combat", "array[object]", "Combat groups: attackers, blockers, blocked, defending", "During combat")));
        gameStateTool.put("examples", listOf(
                example("Mid-game state",
                        "{\n  \"available\": true,\n  \"turn\": 4,\n  \"phase\": \"PRECOMBAT_MAIN\",\n" +
                        "  \"step\": \"PRECOMBAT_MAIN\",\n  \"active_player\": \"Player1\",\n" +
                        "  \"priority_player\": \"Player1\",\n  \"players\": [\n    {\n" +
                        "      \"name\": \"Player1\",\n      \"life\": 18,\n      \"library_size\": 49,\n" +
                        "      \"hand_size\": 5,\n      \"is_active\": true,\n      \"is_you\": true,\n" +
                        "      \"hand\": [\n        { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable\": true },\n" +
                        "        { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true, \"playable\": true }\n" +
                        "      ],\n      \"battlefield\": [\n" +
                        "        { \"name\": \"Mountain\", \"tapped\": false },\n" +
                        "        { \"name\": \"Goblin Guide\", \"tapped\": false, \"power\": 2, \"toughness\": 2 }\n" +
                        "      ],\n      \"mana_pool\": { \"R\": 0 }\n    },\n    {\n" +
                        "      \"name\": \"Player2\",\n      \"life\": 20,\n      \"library_size\": 52,\n" +
                        "      \"hand_size\": 7,\n      \"is_active\": false,\n      \"is_you\": false,\n" +
                        "      \"battlefield\": [\n        { \"name\": \"Island\", \"tapped\": false }\n" +
                        "      ]\n    }\n  ],\n  \"stack\": []\n}")));
        tools.add(gameStateTool);

        // get_oracle_text
        Map<String, Object> getOracleTextTool = new HashMap<>();
        getOracleTextTool.put("name", "get_oracle_text");
        getOracleTextTool.put("description",
                "Get oracle text (rules) for cards. Provide exactly one of: card_name (single), " +
                "card_names (batch array), or object_id (in-game object). " +
                "Single returns {name, rules}. Batch returns {cards: [{name, rules}, ...]}.");
        Map<String, Object> getOracleTextSchema = new HashMap<>();
        getOracleTextSchema.put("type", "object");
        Map<String, Object> getOracleTextProps = new HashMap<>();
        Map<String, Object> cardNameProp = new HashMap<>();
        cardNameProp.put("type", "string");
        cardNameProp.put("description", "Single card name lookup");
        getOracleTextProps.put("card_name", cardNameProp);
        Map<String, Object> cardNamesProp = new HashMap<>();
        cardNamesProp.put("type", "array");
        Map<String, Object> cardNamesItems = new HashMap<>();
        cardNamesItems.put("type", "string");
        cardNamesProp.put("items", cardNamesItems);
        cardNamesProp.put("description", "Batch card name lookup");
        getOracleTextProps.put("card_names", cardNamesProp);
        Map<String, Object> objectIdProp = new HashMap<>();
        objectIdProp.put("type", "string");
        objectIdProp.put("description", "UUID of an in-game object");
        getOracleTextProps.put("object_id", objectIdProp);
        getOracleTextSchema.put("properties", getOracleTextProps);
        getOracleTextSchema.put("additionalProperties", false);
        getOracleTextTool.put("inputSchema", getOracleTextSchema);
        getOracleTextTool.put("outputSchema", outputSchema(
                field("success", "boolean", "Whether the lookup succeeded"),
                field("name", "string", "Card name", "Single card or object_id lookup"),
                field("rules", "array[string]", "Oracle text lines", "Single card or object_id lookup"),
                field("cards", "array[object]", "Array of {name, rules} or {name, error} per card", "Batch lookup (card_names)"),
                field("error", "string", "Error message", "success=false")));
        getOracleTextTool.put("examples", listOf(
                example("Single card",
                        "{\n  \"success\": true,\n  \"name\": \"Lightning Bolt\",\n" +
                        "  \"rules\": [\"Deal 3 damage to any target.\"]\n}"),
                example("Batch lookup",
                        "{\n  \"success\": true,\n  \"cards\": [\n" +
                        "    { \"name\": \"Lightning Bolt\", \"rules\": [\"Deal 3 damage to any target.\"] },\n" +
                        "    { \"name\": \"Counterspell\", \"rules\": [\"Counter target spell.\"] }\n  ]\n}"),
                example("Not found",
                        "{\n  \"success\": false,\n  \"error\": \"not found\"\n}")));
        tools.add(getOracleTextTool);

        // get_action_choices
        Map<String, Object> getChoicesTool = new HashMap<>();
        getChoicesTool.put("name", "get_action_choices");
        getChoicesTool.put("description",
                "Get available choices for the current pending action. Call before choose_action. " +
                "Includes context (phase/turn), players (life totals), and land_drops_used (during your main phase). " +
                "response_type: select (cards to play, attackers, blockers), boolean (yes/no), " +
                "index (target/ability), amount, pile, or multi_amount. " +
                "During combat: combat_phase indicates declare_attackers or declare_blockers.");
        Map<String, Object> getChoicesSchema = new HashMap<>();
        getChoicesSchema.put("type", "object");
        getChoicesSchema.put("properties", new HashMap<>());
        getChoicesSchema.put("additionalProperties", false);
        getChoicesTool.put("inputSchema", getChoicesSchema);
        getChoicesTool.put("outputSchema", outputSchema(
                field("action_pending", "boolean", "Whether an action is pending (false if nothing to do)"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("message", "string", "Human-readable prompt from XMage", "action_pending=true"),
                field("response_type", "string", "How to respond: \"select\", \"boolean\", \"index\", \"amount\", \"pile\", or \"multi_amount\""),
                field("context", "string", "Turn/phase context (e.g. \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\")"),
                field("players", "string", "Life total summary (e.g. \"You(20), Opp(18)\")"),
                field("choices", "array[object]", "Available choices with name, type, index, and type-specific fields", "select, index, or pile response_type"),
                field("your_hand", "array[object]", "Hand cards with name, mana_cost, mana_value", "Mulligan decisions"),
                field("combat_phase", "string", "\"declare_attackers\" or \"declare_blockers\"", "During combat"),
                field("mana_pool", "object", "Current mana pool {R, G, U, W, B, C}", "Mana payment"),
                field("untapped_lands", "integer", "Number of untapped lands", "Mana payment"),
                field("min_amount", "integer", "Minimum allowed value", "amount response_type"),
                field("max_amount", "integer", "Maximum allowed value", "amount response_type")));
        getChoicesTool.put("examples", listOf(
                example("Select (play cards)",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"message\": \"Select card to play or pass priority\",\n" +
                        "  \"response_type\": \"select\",\n" +
                        "  \"context\": \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\",\n" +
                        "  \"players\": \"You(20), Opp(18)\",\n" +
                        "  \"choices\": [\n" +
                        "    { \"name\": \"Lightning Bolt\", \"type\": \"card\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable_abilities\": [\"Cast Lightning Bolt\"], \"index\": 0 },\n" +
                        "    { \"name\": \"Mountain\", \"type\": \"card\", \"playable_abilities\": [\"Play Mountain\"], \"index\": 1 }\n" +
                        "  ],\n  \"untapped_lands\": 2\n}"),
                example("Boolean (mulligan)",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_ASK\",\n" +
                        "  \"message\": \"Mulligan hand?\",\n" +
                        "  \"response_type\": \"boolean\",\n" +
                        "  \"context\": \"T0 PREGAME\",\n" +
                        "  \"players\": \"You(20), Opp(20)\",\n" +
                        "  \"your_hand\": [\n" +
                        "    { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true },\n" +
                        "    { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1 }\n" +
                        "  ],\n  \"hand_size\": 7,\n  \"land_count\": 3\n}")));
        tools.add(getChoicesTool);

        // choose_action
        Map<String, Object> chooseActionTool = new HashMap<>();
        chooseActionTool.put("name", "choose_action");
        chooseActionTool.put("description",
                "Respond to pending action. Use index to pick a choice (card, attacker, blocker, " +
                "target, ability, mana source). Use answer for yes/no, pass priority, or confirm " +
                "combat (true=confirm attackers/blockers). Call get_action_choices first.");
        Map<String, Object> chooseActionSchema = new HashMap<>();
        chooseActionSchema.put("type", "object");
        Map<String, Object> chooseActionProps = new HashMap<>();
        Map<String, Object> indexProp = new HashMap<>();
        indexProp.put("type", "integer");
        indexProp.put("description", "Choice index from get_action_choices (for target/ability/choice and mana source/pool choices)");
        chooseActionProps.put("index", indexProp);
        Map<String, Object> answerProp = new HashMap<>();
        answerProp.put("type", "boolean");
        answerProp.put("description", "Yes/No response. For GAME_ASK: true means YES to the question, false means NO. " +
                "For mulligan: true = YES MULLIGAN (discard hand, draw new cards), false = NO KEEP (keep this hand). " +
                "For GAME_SELECT: false = pass priority (done playing cards this phase), " +
                "true = confirm combat (done declaring attackers/blockers). " +
                "Also false to cancel target/mana selection.");
        chooseActionProps.put("answer", answerProp);
        Map<String, Object> amountProp = new HashMap<>();
        amountProp.put("type", "integer");
        amountProp.put("description", "Amount value (for get_amount actions)");
        chooseActionProps.put("amount", amountProp);
        Map<String, Object> amountsProp = new HashMap<>();
        amountsProp.put("type", "array");
        Map<String, Object> amountsItems = new HashMap<>();
        amountsItems.put("type", "integer");
        amountsProp.put("items", amountsItems);
        amountsProp.put("description", "Multiple amount values (for multi_amount actions)");
        chooseActionProps.put("amounts", amountsProp);
        Map<String, Object> pileProp = new HashMap<>();
        pileProp.put("type", "integer");
        pileProp.put("description", "Pile number: 1 or 2 (for pile choices)");
        chooseActionProps.put("pile", pileProp);
        Map<String, Object> textProp = new HashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "Text value for GAME_CHOOSE_CHOICE (use instead of index to pick any option by name, e.g. a creature type not in the filtered list)");
        chooseActionProps.put("text", textProp);
        chooseActionSchema.put("properties", chooseActionProps);
        chooseActionSchema.put("additionalProperties", false);
        chooseActionTool.put("inputSchema", chooseActionSchema);
        chooseActionTool.put("outputSchema", outputSchema(
                field("success", "boolean", "Whether the action was accepted"),
                field("action_taken", "string", "Description of what was done (e.g. \"selected_0\", \"yes\", \"passed_priority\")", "success=true"),
                field("error", "string", "Error message", "success=false"),
                field("warning", "string", "Warning (e.g. possible game loop detected)", "Loop detection")));
        chooseActionTool.put("examples", listOf(
                example("Index selection",
                        "{\n  \"success\": true,\n  \"action_taken\": \"selected_0\"\n}"),
                example("Boolean answer",
                        "{\n  \"success\": true,\n  \"action_taken\": \"no\"\n}"),
                example("Error",
                        "{\n  \"success\": false,\n  \"error\": \"No pending action\"\n}")));
        tools.add(chooseActionTool);

        // get_my_decklist
        Map<String, Object> getDecklistTool = new HashMap<>();
        getDecklistTool.put("name", "get_my_decklist");
        getDecklistTool.put("description", "Get your original decklist (card names and quantities).");
        Map<String, Object> getDecklistSchema = new HashMap<>();
        getDecklistSchema.put("type", "object");
        getDecklistSchema.put("properties", new HashMap<>());
        getDecklistSchema.put("additionalProperties", false);
        getDecklistTool.put("inputSchema", getDecklistSchema);
        getDecklistTool.put("outputSchema", outputSchema(
                field("cards", "string", "Main deck cards, one per line (e.g. \"4x Lightning Bolt\")"),
                field("sideboard", "string", "Sideboard cards, same format", "Deck has sideboard"),
                field("error", "string", "Error message", "No deck loaded")));
        getDecklistTool.put("examples", listOf(
                example("Decklist loaded",
                        "{\n  \"cards\": \"4x Lightning Bolt\\n4x Goblin Guide\\n20x Mountain\",\n" +
                        "  \"sideboard\": \"2x Smash to Smithereens\\n3x Eidolon of the Great Revel\"\n}")));
        tools.add(getDecklistTool);

        return tools;
    }

    private Map<String, Object> handleToolsList(JsonObject params) {
        Map<String, Object> result = new HashMap<>();
        result.put("tools", getToolDefinitions());
        return result;
    }

    private Map<String, Object> handleToolsCall(JsonObject params) {
        String toolName = getStringOrNull(params, "name");
        if (toolName == null) {
            throw new RuntimeException("Missing required 'name' parameter");
        }
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        Map<String, Object> toolResult;

        switch (toolName) {
            case "is_action_on_me":
                toolResult = callbackHandler.getPendingActionInfo();
                break;

            case "take_action":
                toolResult = callbackHandler.executeDefaultAction();
                break;

            case "get_game_log":
                int maxChars = arguments.has("max_chars") && !arguments.get("max_chars").isJsonNull() ? arguments.get("max_chars").getAsInt() : 0;
                Integer logCursor = arguments.has("cursor") && !arguments.get("cursor").isJsonNull() ? arguments.get("cursor").getAsInt() : null;
                toolResult = callbackHandler.getGameLogChunk(maxChars, logCursor);
                break;

            case "send_chat_message":
                String message = getStringOrNull(arguments, "message");
                if (message == null) {
                    throw new RuntimeException("Missing required 'message' parameter");
                }
                boolean success = callbackHandler.sendChatMessage(message);
                toolResult = new HashMap<>();
                toolResult.put("success", success);
                break;

            case "wait_for_action":
                int timeoutMs = arguments.has("timeout_ms") && !arguments.get("timeout_ms").isJsonNull() ? arguments.get("timeout_ms").getAsInt() : 15000;
                toolResult = callbackHandler.waitForAction(timeoutMs);
                break;

            case "pass_priority":
                int passPriorityTimeout = arguments.has("timeout_ms") && !arguments.get("timeout_ms").isJsonNull() ? arguments.get("timeout_ms").getAsInt() : 30000;
                toolResult = callbackHandler.passPriority(passPriorityTimeout);
                break;

            case "wait_and_get_choices":
                int waitChoicesTimeout = arguments.has("timeout_ms") && !arguments.get("timeout_ms").isJsonNull() ? arguments.get("timeout_ms").getAsInt() : 30000;
                toolResult = callbackHandler.waitAndGetChoices(waitChoicesTimeout);
                break;

            case "auto_pass_until_event":
                int minNewChars = arguments.has("min_new_chars") && !arguments.get("min_new_chars").isJsonNull() ? arguments.get("min_new_chars").getAsInt() : 50;
                int autoPassTimeout = arguments.has("timeout_ms") && !arguments.get("timeout_ms").isJsonNull() ? arguments.get("timeout_ms").getAsInt() : 10000;
                toolResult = callbackHandler.autoPassUntilEvent(minNewChars, autoPassTimeout);
                break;

            case "get_game_state":
                Long stateCursor = arguments.has("cursor") && !arguments.get("cursor").isJsonNull() ? arguments.get("cursor").getAsLong() : null;
                toolResult = callbackHandler.getGameState(stateCursor);
                break;

            case "get_oracle_text":
                String cardName = getStringOrNull(arguments, "card_name");
                String objectId = getStringOrNull(arguments, "object_id");
                String[] cardNames = null;
                if (arguments.has("card_names") && !arguments.get("card_names").isJsonNull()) {
                    JsonArray namesArr = arguments.getAsJsonArray("card_names");
                    cardNames = new String[namesArr.size()];
                    for (int i = 0; i < namesArr.size(); i++) {
                        JsonElement elem = namesArr.get(i);
                        cardNames[i] = elem.isJsonNull() ? null : elem.getAsString();
                    }
                }
                toolResult = callbackHandler.getOracleText(cardName, objectId, cardNames);
                break;

            case "get_action_choices":
                toolResult = callbackHandler.getActionChoices();
                break;

            case "choose_action":
                Integer choiceIndex = arguments.has("index") && !arguments.get("index").isJsonNull() ? arguments.get("index").getAsInt() : null;
                Boolean choiceAnswer = arguments.has("answer") && !arguments.get("answer").isJsonNull() ? arguments.get("answer").getAsBoolean() : null;
                Integer choiceAmount = arguments.has("amount") && !arguments.get("amount").isJsonNull() ? arguments.get("amount").getAsInt() : null;
                int[] choiceAmounts = null;
                if (arguments.has("amounts") && !arguments.get("amounts").isJsonNull()) {
                    JsonArray arr = arguments.getAsJsonArray("amounts");
                    if (arr.size() > 0) {
                        choiceAmounts = new int[arr.size()];
                        for (int i = 0; i < arr.size(); i++) {
                            choiceAmounts[i] = arr.get(i).isJsonNull() ? 0 : arr.get(i).getAsInt();
                        }
                    }
                }
                Integer choicePile = arguments.has("pile") && !arguments.get("pile").isJsonNull() ? arguments.get("pile").getAsInt() : null;
                String choiceText = getStringOrNull(arguments, "text");
                // Treat empty string as "not provided" (some models send all params with defaults)
                if (choiceText != null && choiceText.isEmpty()) choiceText = null;
                toolResult = callbackHandler.chooseAction(choiceIndex, choiceAnswer, choiceAmount, choiceAmounts, choicePile, choiceText);
                break;

            case "get_my_decklist":
                toolResult = callbackHandler.getMyDecklist();
                break;

            default:
                throw new RuntimeException("Unknown tool: " + toolName);
        }

        // Format as MCP tool result
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("type", "text");
        textContent.put("text", gson.toJson(toolResult));
        content.add(textContent);
        result.put("content", content);
        result.put("structuredContent", toolResult);
        result.put("isError", false);

        return result;
    }

    private void sendResponse(JsonElement id, Object result, Object error) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        if (error != null) {
            response.put("error", error);
        } else {
            response.put("result", result);
        }

        String json = gson.toJson(response);
        synchronized (stdout) {
            stdout.println(json);
            stdout.flush();
        }
    }

    private void sendError(JsonElement id, int code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        sendResponse(id, null, error);
    }

    /**
     * Print MCP tool definitions as JSON to stdout.
     * Used by `make mcp-tools` to generate mcp-tools.json.
     */
    public static void main(String[] args) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        System.out.println(gson.toJson(getToolDefinitions()));
    }
}
