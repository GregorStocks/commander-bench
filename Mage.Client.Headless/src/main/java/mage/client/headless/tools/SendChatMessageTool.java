package mage.client.headless.tools;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class SendChatMessageTool {
    @Tool(
        name = "send_chat_message",
        description = "Send a chat message to the game",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the message was sent")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Message to send", required = true) String message) {
        if (message == null) {
            throw new RuntimeException("Missing required 'message' parameter");
        }
        boolean success = handler.sendChatMessage(message);
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        return result;
    }

    public static List<Map<String, Object>> examples() {
        return Arrays.asList(
            example("Success", json(
                "success", true)));
    }
}
