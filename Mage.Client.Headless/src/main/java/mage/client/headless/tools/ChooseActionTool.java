package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class ChooseActionTool {
    @Tool(
        name = "choose_action",
        description = "Respond to pending action. Use index to pick a choice (card, attacker, blocker, "
            + "target, ability, mana source). Use answer for yes/no, pass priority, or confirm "
            + "combat (true=confirm attackers/blockers). Call get_action_choices first.",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the action was accepted"),
            @Tool.Field(name = "action_taken", type = "string", description = "Description of what was done (e.g. \"selected_0\", \"yes\", \"passed_priority\")"),
            @Tool.Field(name = "error", type = "string", description = "Error message"),
            @Tool.Field(name = "error_code", type = "string",
                description = "Machine-readable error code: no_pending_action, missing_param, "
                    + "index_out_of_range, invalid_choice, internal_error, unknown_action_type"),
            @Tool.Field(name = "retryable", type = "boolean",
                description = "Whether the action can be retried with different parameters"),
            @Tool.Field(name = "warning", type = "string", description = "Warning (e.g. possible game loop detected)"),
            @Tool.Field(name = "mana_plan_set", type = "boolean", description = "Whether a mana plan was stored for upcoming payment callbacks"),
            @Tool.Field(name = "mana_plan_size", type = "integer", description = "Number of entries in the stored mana plan")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Choice index from get_action_choices (for target/ability/choice and mana source/pool choices)") Integer index,
            @Param(description = "Yes/No response. For GAME_ASK: true means YES to the question, false means NO. "
                + "For mulligan: true = YES MULLIGAN (discard hand, draw new cards), false = NO KEEP (keep this hand). "
                + "For GAME_SELECT: false = pass priority (done playing cards this phase), "
                + "true = confirm combat (done declaring attackers/blockers). "
                + "Also false to cancel target/mana selection.") Boolean answer,
            @Param(description = "Amount value (for get_amount actions)") Integer amount,
            @Param(description = "Multiple amount values (for multi_amount actions)") int[] amounts,
            @Param(description = "Pile number: 1 or 2 (for pile choices)") Integer pile,
            @Param(description = "Text value for GAME_CHOOSE_CHOICE (use instead of index to pick any option by name, e.g. a creature type not in the filtered list)") String text,
            @Param(description = "JSON array of mana sources to tap when casting a spell. "
                + "Each entry: {\"tap\": \"permanent-id\"} to tap a permanent, "
                + "or {\"pool\": \"RED\"} to spend mana from pool "
                + "(valid types: WHITE, BLUE, BLACK, RED, GREEN, COLORLESS). "
                + "Consumed in order as mana payment callbacks arrive. "
                + "Example: [{\"tap\": \"a1b2-...\"}, {\"tap\": \"c3d4-...\"}]. "
                + "The plan must be COMPLETE — if any entry fails (wrong ID, unavailable permanent) "
                + "or the plan runs out before all mana is paid, the spell is cancelled. "
                + "Avoid multi-ability permanents (filter lands, dual lands) — they cancel the spell. "
                + "For X spells, include entries for both the X pips and the regular cost. "
                + "Mutually exclusive with auto_tap.") String mana_plan,
            @Param(description = "Set true to use the automatic mana tapper. "
                + "WARNING: The autotapper is not smart — it taps the first available source with no color "
                + "awareness and uses a naive heuristic for multi-ability lands. Prefer mana_plan for "
                + "strategic tapping. Only use auto_tap to save tokens when tapping order doesn't matter.") Boolean auto_tap) {
        // Treat empty arrays/strings as "not provided" (some models send all params with defaults)
        if (amounts != null && amounts.length == 0) amounts = null;
        if (text != null && text.isEmpty()) text = null;
        if (mana_plan != null && mana_plan.isBlank()) mana_plan = null;
        return handler.chooseAction(index, answer, amount, amounts, pile, text, mana_plan, auto_tap);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Index selection", json(
                "success", true,
                "action_taken", "selected_0")),
            example("Boolean answer", json(
                "success", true,
                "action_taken", "no")),
            example("Cast with mana plan", json(
                "success", true,
                "action_taken", "selected_2",
                "mana_plan_set", true,
                "mana_plan_size", 3)),
            example("Error", json(
                "success", false,
                "error", "Index 5 out of range (call get_action_choices first)",
                "error_code", "index_out_of_range",
                "retryable", true)));
    }
}
