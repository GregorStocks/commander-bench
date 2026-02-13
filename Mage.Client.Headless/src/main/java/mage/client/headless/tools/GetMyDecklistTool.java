package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class GetMyDecklistTool implements McpTool {
    @Override public String name() { return "get_my_decklist"; }

    @Override public String description() {
        return "Get your original decklist (card names and quantities).";
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("cards", "string", "Main deck cards, one per line (e.g. \"4x Lightning Bolt\")"),
                field("sideboard", "string", "Sideboard cards, same format", "Deck has sideboard"),
                field("error", "string", "Error message", "No deck loaded"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Decklist loaded",
                        "{\n  \"cards\": \"4x Lightning Bolt\\n4x Goblin Guide\\n20x Mountain\",\n" +
                        "  \"sideboard\": \"2x Smash to Smithereens\\n3x Eidolon of the Great Revel\"\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        return handler.getMyDecklist();
    }
}
