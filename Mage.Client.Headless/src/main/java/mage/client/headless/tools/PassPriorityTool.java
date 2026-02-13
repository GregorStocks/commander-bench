package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class PassPriorityTool implements McpTool {
    @Override public String name() { return "pass_priority"; }

    @Override public String description() {
        return "Auto-pass priority until you need to make a decision: playable cards, combat " +
                "(declare attackers/blockers), or non-priority actions. " +
                "Returns action_pending, action_type, actions_passed, has_playable_cards, combat_phase. " +
                "On timeout: action_pending=false, timeout=true.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("timeout_ms", "integer", "Max milliseconds to wait (default 30000)"));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("action_pending", "boolean", "Whether a decision-requiring action was found"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("actions_passed", "integer", "Number of priority passes performed"),
                field("has_playable_cards", "boolean", "Whether you have playable cards in hand", "action_pending=true"),
                field("combat_phase", "string", "\"declare_attackers\" or \"declare_blockers\"", "In combat"),
                field("recent_chat", "array[string]", "Chat messages received since last check", "Chat received"),
                field("player_dead", "boolean", "Whether you died during priority passing", "Player died"),
                field("timeout", "boolean", "Whether the operation timed out", "Timeout"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Playable cards found",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"actions_passed\": 3,\n  \"has_playable_cards\": true\n}"),
                example("Combat phase",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"actions_passed\": 5,\n  \"has_playable_cards\": false,\n" +
                        "  \"combat_phase\": \"declare_attackers\"\n}"),
                example("Timeout",
                        "{\n  \"action_pending\": false,\n  \"actions_passed\": 12,\n  \"timeout\": true\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        int timeout = getInt(arguments, "timeout_ms", 30000);
        return handler.passPriority(timeout);
    }
}
