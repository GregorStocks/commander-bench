package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class SendChatMessageTool implements McpTool {
    @Override public String name() { return "send_chat_message"; }

    @Override public String description() {
        return "Send a chat message to the game";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                listOf("message"),
                param("message", "string", "Message to send"));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("success", "boolean", "Whether the message was sent"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Success",
                        "{\n  \"success\": true\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        String message = getStringOrNull(arguments, "message");
        if (message == null) {
            throw new RuntimeException("Missing required 'message' parameter");
        }
        boolean success = handler.sendChatMessage(message);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        return result;
    }
}
