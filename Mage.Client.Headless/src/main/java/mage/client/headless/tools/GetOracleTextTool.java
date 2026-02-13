package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class GetOracleTextTool {
    @Tool(
        name = "get_oracle_text",
        description = "Get oracle text (rules) for cards. Provide exactly one of: card_name (single), "
            + "card_names (batch array), or object_id (in-game object). "
            + "Single returns {name, rules}. Batch returns {cards: [{name, rules}, ...]}.",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the lookup succeeded"),
            @Tool.Field(name = "name", type = "string", description = "Card name"),
            @Tool.Field(name = "rules", type = "array[string]", description = "Oracle text lines"),
            @Tool.Field(name = "cards", type = "array[object]", description = "Array of {name, rules} or {name, error} per card"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        },
        examples = {
            @Tool.Example(label = "Single card",
                value = "{\n  \"success\": true,\n  \"name\": \"Lightning Bolt\",\n  \"rules\": [\"Deal 3 damage to any target.\"]\n}"),
            @Tool.Example(label = "Batch lookup",
                value = "{\n  \"success\": true,\n  \"cards\": [\n"
                    + "    { \"name\": \"Lightning Bolt\", \"rules\": [\"Deal 3 damage to any target.\"] },\n"
                    + "    { \"name\": \"Counterspell\", \"rules\": [\"Counter target spell.\"] }\n  ]\n}"),
            @Tool.Example(label = "Not found",
                value = "{\n  \"success\": false,\n  \"error\": \"not found\"\n}")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Single card name lookup") String card_name,
            @Param(description = "Batch card name lookup") String[] card_names,
            @Param(description = "UUID of an in-game object") String object_id) {
        return handler.getOracleText(card_name, object_id, card_names);
    }
}
