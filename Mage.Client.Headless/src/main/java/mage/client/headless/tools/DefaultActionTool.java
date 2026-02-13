package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class DefaultActionTool implements McpTool {
    @Override public String name() { return "default_action"; }

    @Override public String description() {
        return "Execute default action (pass priority or first available choice)";
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("success", "boolean", "Whether the action was executed successfully"),
                field("action_type", "string", "The callback method that was handled", "success=true"),
                field("action_taken", "string", "Description of what was done (e.g. \"passed_priority\")", "success=true"),
                field("error", "string", "Error message", "success=false"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Success",
                        "{\n  \"success\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"action_taken\": \"passed_priority\"\n}"),
                example("No pending action",
                        "{\n  \"success\": false,\n  \"error\": \"No pending action\"\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        return handler.executeDefaultAction();
    }
}
