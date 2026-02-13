package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class GetActionChoicesTool implements McpTool {
    @Override public String name() { return "get_action_choices"; }

    @Override public String description() {
        return "Get available choices for the current pending action. Call before choose_action. " +
                "With timeout_ms: blocks like pass_priority until a decision is needed, then returns choices in one call. " +
                "Without timeout_ms: returns immediately (action_pending=false if nothing to do). " +
                "Includes context (phase/turn), players (life totals), and land_drops_used (during your main phase). " +
                "response_type: select (cards to play, attackers, blockers), boolean (yes/no), " +
                "index (target/ability), amount, pile, or multi_amount. " +
                "During combat: combat_phase indicates declare_attackers or declare_blockers.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("timeout_ms", "integer", "Max milliseconds to wait for a decision. When set, auto-passes priority until a decision is needed (like pass_priority + get_action_choices in one call). When omitted, returns immediately."));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("action_pending", "boolean", "Whether an action is pending (false if nothing to do)"),
                field("action_type", "string", "XMage callback method name", "action_pending=true"),
                field("message", "string", "Human-readable prompt from XMage", "action_pending=true"),
                field("response_type", "string", "How to respond: \"select\", \"boolean\", \"index\", \"amount\", \"pile\", or \"multi_amount\""),
                field("context", "string", "Turn/phase context (e.g. \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\")"),
                field("players", "string", "Life total summary (e.g. \"You(20), Opp(18)\")"),
                field("choices", "array[object]", "Available choices with name, type, index, and type-specific fields", "select, index, or pile response_type"),
                field("your_hand", "array[object]", "Hand cards with name, mana_cost, mana_value", "Mulligan decisions"),
                field("combat_phase", "string", "\"declare_attackers\" or \"declare_blockers\"", "During combat"),
                field("mana_pool", "object", "Current mana pool {R, G, U, W, B, C}", "Mana payment"),
                field("untapped_lands", "integer", "Number of untapped lands", "Mana payment"),
                field("min_amount", "integer", "Minimum allowed value", "amount response_type"),
                field("max_amount", "integer", "Maximum allowed value", "amount response_type"),
                field("actions_passed", "integer", "Number of priority passes performed before the decision", "timeout_ms provided"),
                field("recent_chat", "array[string]", "Chat messages received since last check", "timeout_ms provided"),
                field("timeout", "boolean", "Whether the operation timed out", "timeout_ms provided"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Select (play cards)",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_SELECT\",\n" +
                        "  \"message\": \"Select card to play or pass priority\",\n" +
                        "  \"response_type\": \"select\",\n" +
                        "  \"context\": \"T3 PRECOMBAT_MAIN (Player1) YOUR_MAIN\",\n" +
                        "  \"players\": \"You(20), Opp(18)\",\n" +
                        "  \"choices\": [\n" +
                        "    { \"name\": \"Lightning Bolt\", \"type\": \"card\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable_abilities\": [\"Cast Lightning Bolt\"], \"index\": 0 },\n" +
                        "    { \"name\": \"Mountain\", \"type\": \"card\", \"playable_abilities\": [\"Play Mountain\"], \"index\": 1 }\n" +
                        "  ],\n  \"untapped_lands\": 2\n}"),
                example("Boolean (mulligan)",
                        "{\n  \"action_pending\": true,\n  \"action_type\": \"GAME_ASK\",\n" +
                        "  \"message\": \"Mulligan hand?\",\n" +
                        "  \"response_type\": \"boolean\",\n" +
                        "  \"context\": \"T0 PREGAME\",\n" +
                        "  \"players\": \"You(20), Opp(20)\",\n" +
                        "  \"your_hand\": [\n" +
                        "    { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true },\n" +
                        "    { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1 }\n" +
                        "  ],\n  \"hand_size\": 7,\n  \"land_count\": 3\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        if (arguments.has("timeout_ms") && !arguments.get("timeout_ms").isJsonNull()) {
            int timeout = arguments.get("timeout_ms").getAsInt();
            return handler.waitAndGetChoices(timeout);
        } else {
            return handler.getActionChoices();
        }
    }
}
