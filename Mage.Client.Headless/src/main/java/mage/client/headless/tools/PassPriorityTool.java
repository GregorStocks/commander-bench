package mage.client.headless.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class PassPriorityTool {
    @Tool(
        name = "pass_priority",
        description = "Auto-pass priority until you need to make a decision: playable cards, combat "
            + "(declare attackers/blockers), or non-priority actions. "
            + "Returns action_pending, action_type, actions_passed, has_playable_cards, combat_phase. "
            + "On timeout: action_pending=false, timeout=true.",
        output = {
            @Tool.Field(name = "action_pending", type = "boolean", description = "Whether a decision-requiring action was found"),
            @Tool.Field(name = "action_type", type = "string", description = "XMage callback method name"),
            @Tool.Field(name = "actions_passed", type = "integer", description = "Number of priority passes performed"),
            @Tool.Field(name = "has_playable_cards", type = "boolean", description = "Whether you have playable cards in hand"),
            @Tool.Field(name = "combat_phase", type = "string", description = "\"declare_attackers\" or \"declare_blockers\""),
            @Tool.Field(name = "recent_chat", type = "array[string]", description = "Chat messages received since last check"),
            @Tool.Field(name = "player_dead", type = "boolean", description = "Whether you died during priority passing"),
            @Tool.Field(name = "timeout", type = "boolean", description = "Whether the operation timed out")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Max milliseconds to wait (default 30000)") Integer timeout_ms) {
        int timeout = timeout_ms != null ? timeout_ms : 30000;
        return handler.passPriority(timeout);
    }

    public static List<Map<String, Object>> examples() {
        return Arrays.asList(
            example("Playable cards found", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 3,
                "has_playable_cards", true)),
            example("Combat phase", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 5,
                "has_playable_cards", false,
                "combat_phase", "declare_attackers")),
            example("Timeout", json(
                "action_pending", false,
                "actions_passed", 12,
                "timeout", true)));
    }
}
