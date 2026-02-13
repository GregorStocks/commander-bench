package mage.client.headless.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class ChooseActionTool implements McpTool {
    @Override public String name() { return "choose_action"; }

    @Override public String description() {
        return "Respond to pending action. Use index to pick a choice (card, attacker, blocker, " +
                "target, ability, mana source). Use answer for yes/no, pass priority, or confirm " +
                "combat (true=confirm attackers/blockers). Call get_action_choices first.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("index", "integer", "Choice index from get_action_choices (for target/ability/choice and mana source/pool choices)"),
                param("answer", "boolean", "Yes/No response. For GAME_ASK: true means YES to the question, false means NO. " +
                        "For mulligan: true = YES MULLIGAN (discard hand, draw new cards), false = NO KEEP (keep this hand). " +
                        "For GAME_SELECT: false = pass priority (done playing cards this phase), " +
                        "true = confirm combat (done declaring attackers/blockers). " +
                        "Also false to cancel target/mana selection."),
                param("amount", "integer", "Amount value (for get_amount actions)"),
                arrayParam("amounts", "integer", "Multiple amount values (for multi_amount actions)"),
                param("pile", "integer", "Pile number: 1 or 2 (for pile choices)"),
                param("text", "string", "Text value for GAME_CHOOSE_CHOICE (use instead of index to pick any option by name, e.g. a creature type not in the filtered list)"));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("success", "boolean", "Whether the action was accepted"),
                field("action_taken", "string", "Description of what was done (e.g. \"selected_0\", \"yes\", \"passed_priority\")", "success=true"),
                field("error", "string", "Error message", "success=false"),
                field("warning", "string", "Warning (e.g. possible game loop detected)", "Loop detection"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Index selection",
                        "{\n  \"success\": true,\n  \"action_taken\": \"selected_0\"\n}"),
                example("Boolean answer",
                        "{\n  \"success\": true,\n  \"action_taken\": \"no\"\n}"),
                example("Error",
                        "{\n  \"success\": false,\n  \"error\": \"No pending action\"\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        Integer index = getIntOrNull(arguments, "index");
        Boolean answer = getBooleanOrNull(arguments, "answer");
        Integer amount = getIntOrNull(arguments, "amount");
        int[] amounts = null;
        if (arguments.has("amounts") && !arguments.get("amounts").isJsonNull()) {
            JsonArray arr = arguments.getAsJsonArray("amounts");
            if (arr.size() > 0) {
                amounts = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    amounts[i] = arr.get(i).isJsonNull() ? 0 : arr.get(i).getAsInt();
                }
            }
        }
        Integer pile = getIntOrNull(arguments, "pile");
        String text = getStringOrNull(arguments, "text");
        // Treat empty string as "not provided" (some models send all params with defaults)
        if (text != null && text.isEmpty()) text = null;
        return handler.chooseAction(index, answer, amount, amounts, pile, text);
    }
}
