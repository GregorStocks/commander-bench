package mage.client.headless.tools;

import java.util.HashMap;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class SendChatMessageTool {
    @Tool(
        name = "send_chat_message",
        description = "Send a chat message to the game",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the message was sent")
        },
        examples = {
            @Tool.Example(label = "Success",
                value = "{\n  \"success\": true\n}")
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
}
