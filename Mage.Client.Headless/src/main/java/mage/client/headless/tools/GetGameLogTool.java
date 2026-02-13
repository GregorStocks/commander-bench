package mage.client.headless.tools;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpToolRegistry.example;
import static mage.client.headless.tools.McpToolRegistry.json;

public class GetGameLogTool {
    @Tool(
        name = "get_game_log",
        description = "Get game log text. Returns log, total_length, truncated. Use max_chars for recent entries only.",
        output = {
            @Tool.Field(name = "log", type = "string", description = "Game log text (may be truncated if max_chars was set)"),
            @Tool.Field(name = "total_length", type = "integer", description = "Total length of the full game log in characters"),
            @Tool.Field(name = "truncated", type = "boolean", description = "Whether older content was omitted"),
            @Tool.Field(name = "cursor", type = "integer", description = "Cursor to pass to the next get_game_log call"),
            @Tool.Field(name = "cursor_reset", type = "boolean", description = "Whether requested cursor was too old and had to be reset to oldest retained log offset")
        }
    )
    public static Map<String, Object> execute(
            BridgeCallbackHandler handler,
            @Param(description = "Max characters to return (0 or omit for all)") Integer max_chars,
            @Param(description = "Cursor offset from previous get_game_log call. Returns new log text since this offset.") Integer cursor) {
        int mc = max_chars != null ? max_chars : 0;
        return handler.getGameLogChunk(mc, cursor);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Truncated log", json(
                "log", "Turn 3 - Player1: Mountain entered the battlefield...",
                "total_length", 5234,
                "truncated", true,
                "cursor", 5234)),
            example("Cursor delta", json(
                "log", "Player2 casts Swords to Plowshares targeting Goblin Guide.",
                "total_length", 5301,
                "truncated", false,
                "cursor", 5301)));
    }
}
