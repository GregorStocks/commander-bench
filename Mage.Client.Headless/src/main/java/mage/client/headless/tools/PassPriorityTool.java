package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

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
        },
        examples = {
            @Tool.Example(label = "Playable cards found",
                value = "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n  \"actions_passed\": 3,\n  \"has_playable_cards\": true\n}"),
            @Tool.Example(label = "Combat phase",
                value = "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n  \"actions_passed\": 5,\n  \"has_playable_cards\": false,\n  \"combat_phase\": \"declare_attackers\"\n}"),
            @Tool.Example(label = "Timeout",
                value = "{\n  \"action_pending\": false,\n  \"actions_passed\": 12,\n  \"timeout\": true\n}")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Max milliseconds to wait (default 30000)") Integer timeout_ms) {
        int timeout = timeout_ms != null ? timeout_ms : 30000;
        return handler.passPriority(timeout);
    }
}
