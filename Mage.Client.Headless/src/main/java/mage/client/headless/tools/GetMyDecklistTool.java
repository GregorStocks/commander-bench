package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class GetMyDecklistTool {
    @Tool(
        name = "get_my_decklist",
        description = "Get your original decklist (card names and quantities).",
        output = {
            @Tool.Field(name = "cards", type = "string", description = "Main deck cards, one per line (e.g. \"4x Lightning Bolt\")"),
            @Tool.Field(name = "sideboard", type = "string", description = "Sideboard cards, same format"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        }
    )
    public static Map<String, Object> execute(BridgeCallbackHandler handler) {
        return handler.getMyDecklist();
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Decklist loaded", json(
                "cards", "4x Lightning Bolt\n4x Goblin Guide\n20x Mountain",
                "sideboard", "2x Smash to Smithereens\n3x Eidolon of the Great Revel")));
    }
}
