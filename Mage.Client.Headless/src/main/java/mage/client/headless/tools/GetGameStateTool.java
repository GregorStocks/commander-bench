package mage.client.headless.tools;

import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

public class GetGameStateTool {
    @Tool(
        name = "get_game_state",
        description = "Get full game state: turn, phase, players, stack, combat. Each player has life, mana_pool, "
            + "hand (yours only), battlefield (name, tapped, P/T, counters, token/copy/face_down flags), "
            + "graveyard, exile, commanders.",
        output = {
            @Tool.Field(name = "available", type = "boolean", description = "Whether game state is available"),
            @Tool.Field(name = "error", type = "string", description = "Error message"),
            @Tool.Field(name = "cursor", type = "integer", description = "Cursor for the latest known game state"),
            @Tool.Field(name = "unchanged", type = "boolean", description = "True when the provided cursor already matches the latest state"),
            @Tool.Field(name = "turn", type = "integer", description = "Current turn number"),
            @Tool.Field(name = "phase", type = "string", description = "Current phase (e.g. PRECOMBAT_MAIN, COMBAT)"),
            @Tool.Field(name = "step", type = "string", description = "Current step within the phase"),
            @Tool.Field(name = "active_player", type = "string", description = "Name of the player whose turn it is"),
            @Tool.Field(name = "priority_player", type = "string", description = "Name of the player who currently has priority"),
            @Tool.Field(name = "players", type = "array[object]", description = "Player objects: name, life, library_size, hand_size, is_active, is_you, hand (yours only), battlefield, graveyard, exile, mana_pool, counters, commanders"),
            @Tool.Field(name = "stack", type = "array[object]", description = "Stack objects: name, rules, target_count"),
            @Tool.Field(name = "combat", type = "array[object]", description = "Combat groups: attackers, blockers, blocked, defending")
        },
        examples = {
            @Tool.Example(label = "Mid-game state",
                value = "{\n  \"available\": true,\n  \"turn\": 4,\n  \"phase\": \"PRECOMBAT_MAIN\",\n"
                    + "  \"step\": \"PRECOMBAT_MAIN\",\n  \"active_player\": \"Player1\",\n"
                    + "  \"priority_player\": \"Player1\",\n  \"players\": [\n    {\n"
                    + "      \"name\": \"Player1\",\n      \"life\": 18,\n      \"library_size\": 49,\n"
                    + "      \"hand_size\": 5,\n      \"is_active\": true,\n      \"is_you\": true,\n"
                    + "      \"hand\": [\n        { \"name\": \"Lightning Bolt\", \"mana_cost\": \"{R}\", \"mana_value\": 1, \"playable\": true },\n"
                    + "        { \"name\": \"Mountain\", \"mana_value\": 0, \"is_land\": true, \"playable\": true }\n"
                    + "      ],\n      \"battlefield\": [\n"
                    + "        { \"name\": \"Mountain\", \"tapped\": false },\n"
                    + "        { \"name\": \"Goblin Guide\", \"tapped\": false, \"power\": 2, \"toughness\": 2 }\n"
                    + "      ],\n      \"mana_pool\": { \"R\": 0 }\n    },\n    {\n"
                    + "      \"name\": \"Player2\",\n      \"life\": 20,\n      \"library_size\": 52,\n"
                    + "      \"hand_size\": 7,\n      \"is_active\": false,\n      \"is_you\": false,\n"
                    + "      \"battlefield\": [\n        { \"name\": \"Island\", \"tapped\": false }\n"
                    + "      ]\n    }\n  ],\n  \"stack\": []\n}")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "State cursor from previous get_game_state call. If unchanged, returns a compact payload.") Long cursor) {
        return handler.getGameState(cursor);
    }
}
