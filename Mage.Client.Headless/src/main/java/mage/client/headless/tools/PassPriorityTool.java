package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class PassPriorityTool {
    @Tool(
        name = "pass_priority",
        description = "Pass priority. Blocks until you have a pending action (playable cards, "
            + "combat, non-priority action like mulligan/targeting). "
            + "With until: skips ahead to a target step or phase. "
            + "Step values (current turn, client-side): upkeep, draw, precombat_main, "
            + "begin_combat, declare_attackers, declare_blockers, end_combat, postcombat_main. "
            + "Cross-turn values (server-side): end_of_turn, my_turn, stack_resolved. "
            + "Always stops for combat and non-priority actions. "
            + "Auto-handles mechanical callbacks (mana payment failures, "
            + "optional targets with no legal targets). "
            + "Returns stop_reason indicating why the call returned.",
        output = {
            @Tool.Field(name = "action_pending", type = "boolean", description = "Whether a decision-requiring action was found"),
            @Tool.Field(name = "action_type", type = "string", description = "XMage callback method name"),
            @Tool.Field(name = "actions_passed", type = "integer", description = "Number of priority passes performed"),
            @Tool.Field(name = "has_playable_cards", type = "boolean", description = "Whether you have playable cards in hand"),
            @Tool.Field(name = "combat_phase", type = "string", description = "\"declare_attackers\" or \"declare_blockers\""),
            @Tool.Field(name = "current_step", type = "string", description = "Current game step (only for reached_step/step_not_reached)"),
            @Tool.Field(name = "recent_chat", type = "array[string]", description = "Chat messages received since last check"),
            @Tool.Field(name = "player_dead", type = "boolean", description = "Whether you died during priority passing"),
            @Tool.Field(name = "stop_reason", type = "string",
                description = "Why the call returned: playable_cards, combat, non_priority_action, "
                    + "game_over, reached_step (target step reached), step_not_reached (turn ended without reaching step)")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(
                description = "Skip ahead to a target. "
                    + "Step values yield within the current turn (client-side): "
                    + "upkeep, draw, precombat_main, begin_combat, declare_attackers, "
                    + "declare_blockers, end_combat, postcombat_main. "
                    + "Cross-turn values use server-side yield: "
                    + "end_of_turn (skip rest of turn), my_turn (skip to your next turn), "
                    + "stack_resolved (wait for stack to resolve). "
                    + "Omit to block until next actionable priority.",
                allowed_values = {
                    "upkeep", "draw", "precombat_main", "begin_combat",
                    "declare_attackers", "declare_blockers",
                    "end_combat", "postcombat_main",
                    "end_of_turn", "my_turn", "stack_resolved"
                }
            ) String until) {
        return handler.passPriority(until);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Playable cards found", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 3,
                "has_playable_cards", true,
                "stop_reason", "playable_cards")),
            example("Combat phase", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 5,
                "has_playable_cards", false,
                "combat_phase", "declare_attackers",
                "stop_reason", "combat")),
            example("Non-priority action (mulligan, targeting)", json(
                "action_pending", true,
                "action_type", "GAME_ASK",
                "actions_passed", 0,
                "stop_reason", "non_priority_action")),
            example("Yield until next turn", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 8,
                "has_playable_cards", true,
                "stop_reason", "playable_cards")),
            example("Yield to step (reached)", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 12,
                "current_step", "Declare Attackers",
                "stop_reason", "reached_step")),
            example("Yield to step (turn ended)", json(
                "action_pending", true,
                "action_type", "GAME_SELECT",
                "actions_passed", 6,
                "current_step", "Upkeep",
                "stop_reason", "step_not_reached")));
    }
}
