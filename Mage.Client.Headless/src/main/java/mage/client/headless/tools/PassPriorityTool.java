package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class PassPriorityTool {
    @Tool(
        name = "pass_priority",
        description = "Pass priority. Without yield_until: passes once and returns. "
            + "With yield_until: uses XMage's server-side yield system to efficiently skip ahead "
            + "(like F4-F11 in the GUI). With yield_until_step: client-side yield that auto-passes "
            + "until a specific game step within the current turn. Still stops for combat and "
            + "non-priority actions. Auto-handles mechanical callbacks (mana payment failures, "
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
                    + "passed (single pass, no yield), no_action (nothing pending), "
                    + "reached_step (target step reached), step_not_reached (turn ended without reaching step)")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(
                description = "Yield mode: skip ahead using XMage's server-side yield. "
                    + "end_of_turn=F5, next_turn=F4 (stop on stack), "
                    + "next_turn_skip_stack=F6, next_main=F7, "
                    + "stack_resolved=F10, my_turn=F9, "
                    + "end_step_before_my_turn=F11. "
                    + "Omit to pass once and return. Cannot combine with yield_until_step.",
                allowed_values = {
                    "end_of_turn", "next_turn", "next_turn_skip_stack",
                    "next_main", "stack_resolved", "my_turn",
                    "end_step_before_my_turn"
                }
            ) String yield_until,
            @Param(
                description = "Yield to a specific game step (client-side). "
                    + "Auto-passes priority and playable cards until the target step within "
                    + "the current turn. Still stops for combat (declare attackers/blockers) "
                    + "and non-priority actions (mandatory triggers). "
                    + "Returns step_not_reached if the turn ends first. "
                    + "Cannot combine with yield_until.",
                allowed_values = {
                    "upkeep", "draw", "precombat_main", "begin_combat",
                    "declare_attackers", "declare_blockers",
                    "end_combat", "postcombat_main", "end_turn"
                }
            ) String yield_until_step) {
        return handler.passPriority(yield_until, yield_until_step);
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
            example("Single pass (no yield)", json(
                "action_pending", false,
                "actions_passed", 1,
                "stop_reason", "passed")),
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
