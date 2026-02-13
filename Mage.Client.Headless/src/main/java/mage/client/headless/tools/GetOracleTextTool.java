package mage.client.headless.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class GetOracleTextTool implements McpTool {
    @Override public String name() { return "get_oracle_text"; }

    @Override public String description() {
        return "Get oracle text (rules) for cards. Provide exactly one of: card_name (single), " +
                "card_names (batch array), or object_id (in-game object). " +
                "Single returns {name, rules}. Batch returns {cards: [{name, rules}, ...]}.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("card_name", "string", "Single card name lookup"),
                arrayParam("card_names", "string", "Batch card name lookup"),
                param("object_id", "string", "UUID of an in-game object"));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("success", "boolean", "Whether the lookup succeeded"),
                field("name", "string", "Card name", "Single card or object_id lookup"),
                field("rules", "array[string]", "Oracle text lines", "Single card or object_id lookup"),
                field("cards", "array[object]", "Array of {name, rules} or {name, error} per card", "Batch lookup (card_names)"),
                field("error", "string", "Error message", "success=false"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Single card",
                        "{\n  \"success\": true,\n  \"name\": \"Lightning Bolt\",\n" +
                        "  \"rules\": [\"Deal 3 damage to any target.\"]\n}"),
                example("Batch lookup",
                        "{\n  \"success\": true,\n  \"cards\": [\n" +
                        "    { \"name\": \"Lightning Bolt\", \"rules\": [\"Deal 3 damage to any target.\"] },\n" +
                        "    { \"name\": \"Counterspell\", \"rules\": [\"Counter target spell.\"] }\n  ]\n}"),
                example("Not found",
                        "{\n  \"success\": false,\n  \"error\": \"not found\"\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        String cardName = getStringOrNull(arguments, "card_name");
        String objectId = getStringOrNull(arguments, "object_id");
        String[] cardNames = null;
        if (arguments.has("card_names") && !arguments.get("card_names").isJsonNull()) {
            JsonArray namesArr = arguments.getAsJsonArray("card_names");
            cardNames = new String[namesArr.size()];
            for (int i = 0; i < namesArr.size(); i++) {
                JsonElement elem = namesArr.get(i);
                cardNames[i] = elem.isJsonNull() ? null : elem.getAsString();
            }
        }
        return handler.getOracleText(cardName, objectId, cardNames);
    }
}
