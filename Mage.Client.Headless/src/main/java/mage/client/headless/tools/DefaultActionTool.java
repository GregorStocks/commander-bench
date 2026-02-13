package mage.client.headless.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class DefaultActionTool {
    @Tool(
        name = "default_action",
        description = "Execute default action (pass priority or first available choice)",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the action was executed successfully"),
            @Tool.Field(name = "action_type", type = "string", description = "The callback method that was handled"),
            @Tool.Field(name = "action_taken", type = "string", description = "Description of what was done (e.g. \"passed_priority\")"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        }
    )
    public static Map<String, Object> execute(BridgeCallbackHandler handler) {
        return handler.executeDefaultAction();
    }

    public static List<Map<String, Object>> examples() {
        return Arrays.asList(
            example("Success", json(
                "success", true,
                "action_type", "GAME_SELECT",
                "action_taken", "passed_priority")),
            example("No pending action", json(
                "success", false,
                "error", "No pending action")));
    }
}
