package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class GetActionChoicesTool {
    @Tool(
        name = "get_action_choices",
        description = "Get available choices for the current pending action. Call before choose_action. "
            + "With timeout_ms: blocks like pass_priority until a decision is needed, then returns choices in one call. "
            + "Without timeout_ms: returns immediately (action_pending=false if nothing to do). "
            + "Includes context (phase/turn), players (life totals), and land_drops_used (during your main phase). "
            + "response_type: select (cards to play, attackers, blockers), boolean (yes/no), "
            + "index (target/ability), amount, pile, or multi_amount. "
            + "During combat: combat_phase indicates declare_attackers or declare_blockers.",
        output = {
            @Tool.Field(name = "action_pending", type = "boolean", description = "Whether an action is pending (false if nothing to do)"),
            @Tool.Field(name = "action_type", type = "string", description = "XMage callback method name"),
            @Tool.Field(name = "message", type = "string", description = "Human-readable prompt from XMage"),
            @Tool.Field(name = "response_type", type = "string", description = "How to respond: \"select\", \"boolean\", \"index\", \"amount\", \"pile\", or \"multi_amount\""),
            @Tool.Field(name = "context", type = "string", description = "Turn/phase context (e.g. \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\")"),
            @Tool.Field(name = "players", type = "string", description = "Life total summary (e.g. \"You(20), Opp(18)\")"),
            @Tool.Field(name = "choices", type = "array[object]", description = "Available choices with name, type, index, and type-specific fields"),
            @Tool.Field(name = "your_hand", type = "array[object]", description = "Hand cards with name, mana_cost, mana_value"),
            @Tool.Field(name = "combat_phase", type = "string", description = "\"declare_attackers\" or \"declare_blockers\""),
            @Tool.Field(name = "mana_pool", type = "object", description = "Current mana pool {R, G, U, W, B, C}"),
            @Tool.Field(name = "untapped_lands", type = "integer", description = "Number of untapped lands"),
            @Tool.Field(name = "min_amount", type = "integer", description = "Minimum allowed value"),
            @Tool.Field(name = "max_amount", type = "integer", description = "Maximum allowed value"),
            @Tool.Field(name = "actions_passed", type = "integer", description = "Number of priority passes performed before the decision"),
            @Tool.Field(name = "recent_chat", type = "array[string]", description = "Chat messages received since last check"),
            @Tool.Field(name = "timeout", type = "boolean", description = "Whether the operation timed out")
        },
        examples = {
            @Tool.Example(label = "Select (play cards)",
                value = "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n"
                    + "  \"message\": \"Select card to play or pass priority\",\n"
                    + "  \"response_type\": \"select\",\n"
                    + "  \"context\": \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\",\n"
                    + "  \"players\": \"You(20), Opp(18)\",\n"
                    + "  \"choices\": [\n"
                    + "    { \"name\": \"Lightning Bolt\", \"type\": \"card\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable_abilities\": [\"Cast Lightning Bolt\"], \"index\": 0 },\n"
                    + "    { \"name\": \"Mountain\", \"type\": \"card\", \"playable_abilities\": [\"Play Mountain\"], \"index\": 1 }\n"
                    + "  ],\n  \"untapped_lands\": 2\n}"),
            @Tool.Example(label = "Boolean (mulligan)",
                value = "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_ASK\",\n"
                    + "  \"message\": \"Mulligan hand?\",\n"
                    + "  \"response_type\": \"boolean\",\n"
                    + "  \"context\": \"T0 PREGAME\",\n"
                    + "  \"players\": \"You(20), Opp(20)\",\n"
                    + "  \"your_hand\": [\n"
                    + "    { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true },\n"
                    + "    { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1 }\n"
                    + "  ],\n  \"hand_size\": 7,\n  \"land_count\": 3\n}")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Max milliseconds to wait for a decision. When set, auto-passes priority until a decision is needed (like pass_priority + get_action_choices in one call). When omitted, returns immediately.") Integer timeout_ms) {
        if (timeout_ms != null) {
            return handler.waitAndGetChoices(timeout_ms);
        } else {
            return handler.getActionChoices();
        }
    }
}
