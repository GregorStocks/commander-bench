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
 * Exposes eleven tools:
 * - is_action_on_me: Check if action is pending
 * - take_action: Execute default action
 * - wait_for_action: Block until action is pending (or timeout)
 * - get_game_log: Get game log text
 * - get_game_state: Get structured game state
 * - send_chat_message: Send a chat message
 * - get_oracle_text: Look up card rules
 * - auto_pass_until_event: Auto-pass and wait for game state changes
 * - get_action_choices: Get detailed choices for pending action
 * - choose_action: Respond with a specific choice
 * - get_my_decklist: Get original decklist
 */
public class McpServer {

    private static final Logger logger = Logger.getLogger(McpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "xmage-skeleton";
    private static final String SERVER_VERSION = "1.0.0";

    private final SkeletonCallbackHandler callbackHandler;
    private final Gson gson;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PrintWriter stdout;
    private boolean initialized = false;

    public McpServer(SkeletonCallbackHandler callbackHandler) {
        this.callbackHandler = callbackHandler;
        this.gson = new GsonBuilder().create();
        this.stdout = new PrintWriter(System.out, true);
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
        getLogSchema.put("properties", getLogProps);
        getLogSchema.put("additionalProperties", false);
        getLogTool.put("inputSchema", getLogSchema);
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
        tools.add(waitActionTool);

        // pass_priority
        Map<String, Object> passPriorityTool = new HashMap<>();
        passPriorityTool.put("name", "pass_priority");
        passPriorityTool.put("description",
                "Auto-pass priority until you can play cards or a non-priority decision is needed. " +
                "Returns action_pending, action_type, actions_passed, has_playable_cards. " +
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
        tools.add(passPriorityTool);

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
        gameStateSchema.put("properties", new HashMap<>());
        gameStateSchema.put("additionalProperties", false);
        gameStateTool.put("inputSchema", gameStateSchema);
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
        tools.add(getOracleTextTool);

        // get_action_choices
        Map<String, Object> getChoicesTool = new HashMap<>();
        getChoicesTool.put("name", "get_action_choices");
        getChoicesTool.put("description",
                "Get available choices for the current pending action. Call before choose_action. " +
                "Includes context (phase/turn) and players (life totals). " +
                "response_type: select (cards to play), boolean (yes/no), index (target/ability), " +
                "amount, pile, or multi_amount.");
        Map<String, Object> getChoicesSchema = new HashMap<>();
        getChoicesSchema.put("type", "object");
        getChoicesSchema.put("properties", new HashMap<>());
        getChoicesSchema.put("additionalProperties", false);
        getChoicesTool.put("inputSchema", getChoicesSchema);
        tools.add(getChoicesTool);

        // choose_action
        Map<String, Object> chooseActionTool = new HashMap<>();
        chooseActionTool.put("name", "choose_action");
        chooseActionTool.put("description",
                "Respond to pending action. Use index to pick a choice, answer for yes/no or pass, " +
                "amount/amounts for numeric, pile for pile choice. Call get_action_choices first.");
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
                "For GAME_SELECT: false = pass priority (done playing cards this phase). " +
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
        chooseActionSchema.put("properties", chooseActionProps);
        chooseActionSchema.put("additionalProperties", false);
        chooseActionTool.put("inputSchema", chooseActionSchema);
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
        tools.add(getDecklistTool);

        return tools;
    }

    private Map<String, Object> handleToolsList(JsonObject params) {
        Map<String, Object> result = new HashMap<>();
        result.put("tools", getToolDefinitions());
        return result;
    }

    private Map<String, Object> handleToolsCall(JsonObject params) {
        String toolName = params.get("name").getAsString();
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
                int maxChars = arguments.has("max_chars") ? arguments.get("max_chars").getAsInt() : 0;
                String log = callbackHandler.getGameLog(maxChars);
                int totalLength = callbackHandler.getGameLogLength();
                toolResult = new HashMap<>();
                toolResult.put("log", log);
                toolResult.put("total_length", totalLength);
                toolResult.put("truncated", log.length() < totalLength);
                break;

            case "send_chat_message":
                String message = arguments.get("message").getAsString();
                boolean success = callbackHandler.sendChatMessage(message);
                toolResult = new HashMap<>();
                toolResult.put("success", success);
                break;

            case "wait_for_action":
                int timeoutMs = arguments.has("timeout_ms") ? arguments.get("timeout_ms").getAsInt() : 15000;
                toolResult = callbackHandler.waitForAction(timeoutMs);
                break;

            case "pass_priority":
                int passPriorityTimeout = arguments.has("timeout_ms") ? arguments.get("timeout_ms").getAsInt() : 30000;
                toolResult = callbackHandler.passPriority(passPriorityTimeout);
                break;

            case "auto_pass_until_event":
                int minNewChars = arguments.has("min_new_chars") ? arguments.get("min_new_chars").getAsInt() : 50;
                int autoPassTimeout = arguments.has("timeout_ms") ? arguments.get("timeout_ms").getAsInt() : 10000;
                toolResult = callbackHandler.autoPassUntilEvent(minNewChars, autoPassTimeout);
                break;

            case "get_game_state":
                toolResult = callbackHandler.getGameState();
                break;

            case "get_oracle_text":
                String cardName = arguments.has("card_name") ? arguments.get("card_name").getAsString() : null;
                String objectId = arguments.has("object_id") ? arguments.get("object_id").getAsString() : null;
                String[] cardNames = null;
                if (arguments.has("card_names")) {
                    JsonArray namesArr = arguments.getAsJsonArray("card_names");
                    cardNames = new String[namesArr.size()];
                    for (int i = 0; i < namesArr.size(); i++) {
                        cardNames[i] = namesArr.get(i).getAsString();
                    }
                }
                toolResult = callbackHandler.getOracleText(cardName, objectId, cardNames);
                break;

            case "get_action_choices":
                toolResult = callbackHandler.getActionChoices();
                break;

            case "choose_action":
                Integer choiceIndex = arguments.has("index") ? arguments.get("index").getAsInt() : null;
                Boolean choiceAnswer = arguments.has("answer") ? arguments.get("answer").getAsBoolean() : null;
                Integer choiceAmount = arguments.has("amount") ? arguments.get("amount").getAsInt() : null;
                int[] choiceAmounts = null;
                if (arguments.has("amounts")) {
                    JsonArray arr = arguments.getAsJsonArray("amounts");
                    choiceAmounts = new int[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        choiceAmounts[i] = arr.get(i).getAsInt();
                    }
                }
                Integer choicePile = arguments.has("pile") ? arguments.get("pile").getAsInt() : null;
                toolResult = callbackHandler.chooseAction(choiceIndex, choiceAnswer, choiceAmount, choiceAmounts, choicePile);
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
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(getToolDefinitions()));
    }
}
