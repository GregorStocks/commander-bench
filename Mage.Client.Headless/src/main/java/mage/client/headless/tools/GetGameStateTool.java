package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class GetGameStateTool implements McpTool {
    @Override public String name() { return "get_game_state"; }

    @Override public String description() {
        return "Get full game state: turn, phase, players, stack, combat. Each player has life, mana_pool, " +
                "hand (yours only), battlefield (name, tapped, P/T, counters, token/copy/face_down flags), " +
                "graveyard, exile, commanders.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("cursor", "integer", "State cursor from previous get_game_state call. If unchanged, returns a compact payload."));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("available", "boolean", "Whether game state is available"),
                field("error", "string", "Error message", "available=false"),
                field("cursor", "integer", "Cursor for the latest known game state"),
                field("unchanged", "boolean", "True when the provided cursor already matches the latest state"),
                field("turn", "integer", "Current turn number"),
                field("phase", "string", "Current phase (e.g. PRECOMBAT_MAIN, COMBAT)"),
                field("step", "string", "Current step within the phase"),
                field("active_player", "string", "Name of the player whose turn it is"),
                field("priority_player", "string", "Name of the player who currently has priority"),
                field("players", "array[object]", "Player objects: name, life, library_size, hand_size, is_active, is_you, hand (yours only), battlefield, graveyard, exile, mana_pool, counters, commanders"),
                field("stack", "array[object]", "Stack objects: name, rules, target_count"),
                field("combat", "array[object]", "Combat groups: attackers, blockers, blocked, defending", "During combat"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Mid-game state",
                        "{\n  \"available\": true,\n  \"turn\": 4,\n  \"phase\": \"PRECOMBAT_MAIN\",\n" +
                        "  \"step\": \"PRECOMBAT_MAIN\",\n  \"active_player\": \"Player1\",\n" +
                        "  \"priority_player\": \"Player1\",\n  \"players\": [\n    {\n" +
                        "      \"name\": \"Player1\",\n      \"life\": 18,\n      \"library_size\": 49,\n" +
                        "      \"hand_size\": 5,\n      \"is_active\": true,\n      \"is_you\": true,\n" +
                        "      \"hand\": [\n        { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable\": true },\n" +
                        "        { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true, \"playable\": true }\n" +
                        "      ],\n      \"battlefield\": [\n" +
                        "        { \"name\": \"Mountain\", \"tapped\": false },\n" +
                        "        { \"name\": \"Goblin Guide\", \"tapped\": false, \"power\": 2, \"toughness\": 2 }\n" +
                        "      ],\n      \"mana_pool\": { \"R\": 0 }\n    },\n    {\n" +
                        "      \"name\": \"Player2\",\n      \"life\": 20,\n      \"library_size\": 52,\n" +
                        "      \"hand_size\": 7,\n      \"is_active\": false,\n      \"is_you\": false,\n" +
                        "      \"battlefield\": [\n        { \"name\": \"Island\", \"tapped\": false }\n" +
                        "      ]\n    }\n  ],\n  \"stack\": []\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        Long cursor = getLongOrNull(arguments, "cursor");
        return handler.getGameState(cursor);
    }
}
