package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class GetOracleTextTool {
    @Tool(
        name = "get_oracle_text",
        description = "Get oracle text (rules) for cards. Provide exactly one of: card_name (single), "
            + "card_names (batch array), object_id (in-game object), or object_ids (batch array of in-game objects). "
            + "Single returns {name, rules}. Batch returns {cards: [{name, rules}, ...]}.",
        output = {
            @Tool.Field(name = "success", type = "boolean", description = "Whether the lookup succeeded"),
            @Tool.Field(name = "name", type = "string", description = "Card name"),
            @Tool.Field(name = "rules", type = "array[string]", description = "Oracle text lines"),
            @Tool.Field(name = "cards", type = "array[object]", description = "Array of {name, rules} or {name, error} per card"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Single card name lookup") String card_name,
            @Param(description = "Batch card name lookup") String[] card_names,
            @Param(description = "UUID of an in-game object") String object_id,
            @Param(description = "Batch in-game object UUID lookup") String[] object_ids) {
        return handler.getOracleText(card_name, object_id, card_names, object_ids);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Single card", json(
                "success", true,
                "name", "Lightning Bolt",
                "rules", List.of("Deal 3 damage to any target."))),
            example("Batch card_names lookup", json(
                "success", true,
                "cards", List.of(
                    json("name", "Lightning Bolt", "rules", List.of("Deal 3 damage to any target.")),
                    json("name", "Counterspell", "rules", List.of("Counter target spell."))))),
            example("Batch object_ids lookup", json(
                "success", true,
                "cards", List.of(
                    json("object_id", "abc-123", "name", "Lightning Bolt", "rules", List.of("Deal 3 damage to any target.")),
                    json("object_id", "def-456", "error", "not found")))),
            example("Not found", json(
                "success", false,
                "error", "not found")));
    }
}
