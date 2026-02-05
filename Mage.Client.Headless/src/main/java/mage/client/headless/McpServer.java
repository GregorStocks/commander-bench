package mage.client.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * Exposes four tools:
 * - is_action_on_me: Check if action is pending
 * - take_action: Execute default action
 * - get_game_log: Get game log text
 * - send_chat_message: Send a chat message
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

    private Map<String, Object> handleToolsList(JsonObject params) {
        List<Map<String, Object>> tools = new ArrayList<>();

        // is_action_on_me
        Map<String, Object> isActionTool = new HashMap<>();
        isActionTool.put("name", "is_action_on_me");
        isActionTool.put("description", "Check if game action is currently required from this player");
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
        getLogTool.put("description", "Get the game log text");
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

        // get_oracle_text
        Map<String, Object> getOracleTextTool = new HashMap<>();
        getOracleTextTool.put("name", "get_oracle_text");
        getOracleTextTool.put("description",
                "Get oracle text (rules) for a card by name or in-game object ID. " +
                "Provide exactly one of card_name or object_id (not both).");
        Map<String, Object> getOracleTextSchema = new HashMap<>();
        getOracleTextSchema.put("type", "object");
        Map<String, Object> getOracleTextProps = new HashMap<>();
        Map<String, Object> cardNameProp = new HashMap<>();
        cardNameProp.put("type", "string");
        cardNameProp.put("description", "Card name for database lookup (e.g., 'Lightning Bolt')");
        getOracleTextProps.put("card_name", cardNameProp);
        Map<String, Object> objectIdProp = new HashMap<>();
        objectIdProp.put("type", "string");
        objectIdProp.put("description", "UUID of an in-game object for dynamic rules");
        getOracleTextProps.put("object_id", objectIdProp);
        getOracleTextSchema.put("properties", getOracleTextProps);
        getOracleTextSchema.put("additionalProperties", false);
        getOracleTextTool.put("inputSchema", getOracleTextSchema);
        tools.add(getOracleTextTool);

        Map<String, Object> result = new HashMap<>();
        result.put("tools", tools);
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
                toolResult.put("returned_length", log.length());
                toolResult.put("truncated", log.length() < totalLength);
                break;

            case "send_chat_message":
                String message = arguments.get("message").getAsString();
                boolean success = callbackHandler.sendChatMessage(message);
                toolResult = new HashMap<>();
                toolResult.put("success", success);
                break;

            case "get_oracle_text":
                String cardName = arguments.has("card_name") ? arguments.get("card_name").getAsString() : null;
                String objectId = arguments.has("object_id") ? arguments.get("object_id").getAsString() : null;
                toolResult = callbackHandler.getOracleText(cardName, objectId);
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
}
