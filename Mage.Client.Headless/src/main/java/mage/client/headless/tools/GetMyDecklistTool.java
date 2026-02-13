package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class GetMyDecklistTool {
    @Tool(
        name = "get_my_decklist",
        description = "Get your original decklist (card names and quantities).",
        output = {
            @Tool.Field(name = "cards", type = "string", description = "Main deck cards, one per line (e.g. \"4x Lightning Bolt\")"),
            @Tool.Field(name = "sideboard", type = "string", description = "Sideboard cards, same format"),
            @Tool.Field(name = "error", type = "string", description = "Error message")
        },
        examples = {
            @Tool.Example(label = "Decklist loaded",
                value = "{\n  \"cards\": \"4x Lightning Bolt\\n4x Goblin Guide\\n20x Mountain\",\n"
                    + "  \"sideboard\": \"2x Smash to Smithereens\\n3x Eidolon of the Great Revel\"\n}")
        }
    )
    public static Map<String, Object> execute(BridgeCallbackHandler handler) {
        return handler.getMyDecklist();
    }
}
