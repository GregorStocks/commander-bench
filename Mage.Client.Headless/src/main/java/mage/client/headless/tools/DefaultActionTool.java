package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class DefaultActionTool {
    @Tool(
        name = "default_action",
        description = "Execute default action (pass priority or first available choice)",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the action was executed successfully"),
            @Tool.Field(name = "action_type", type = "string", description = "The callback method that was handled"),
            @Tool.Field(name = "action_taken", type = "string", description = "Description of what was done (e.g. \"passed_priority\")"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        },
        examples = {
            @Tool.Example(label = "Success",
                value = "{\n  \"success\": true,\n  \"action_type\": \"GAME_SELECT\",\n  \"action_taken\": \"passed_priority\"\n}"),
            @Tool.Example(label = "No pending action",
                value = "{\n  \"success\": false,\n  \"error\": \"No pending action\"\n}")
        }
    )
    public static Map<String, Object> execute(BridgeCallbackHandler handler) {
        return handler.executeDefaultAction();
    }
}
