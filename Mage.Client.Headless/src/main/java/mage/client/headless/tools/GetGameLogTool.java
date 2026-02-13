package mage.client.headless.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import mage.client.headless.BridgeCallbackHandler;

import static mage.client.headless.tools.McpTool.*;

public class GetGameLogTool implements McpTool {
    @Override public String name() { return "get_game_log"; }

    @Override public String description() {
        return "Get game log text. Returns log, total_length, truncated. Use max_chars for recent entries only.";
    }

    @Override public Map<String, Object> inputSchema() {
        return McpTool.inputSchema(
                param("max_chars", "integer", "Max characters to return (0 or omit for all)"),
                param("cursor", "integer", "Cursor offset from previous get_game_log call. Returns new log text since this offset."));
    }

    @Override public Map<String, Object> outputSchema() {
        return McpTool.outputSchema(
                field("log", "string", "Game log text (may be truncated if max_chars was set)"),
                field("total_length", "integer", "Total length of the full game log in characters"),
                field("truncated", "boolean", "Whether older content was omitted"),
                field("cursor", "integer", "Cursor to pass to the next get_game_log call"),
                field("cursor_reset", "boolean", "Whether requested cursor was too old and had to be reset to oldest retained log offset", "cursor was stale"));
    }

    @Override public List<Map<String, Object>> examples() {
        return listOf(
                example("Truncated log",
                        "{\n  \"log\": \"Turn 3 - Player1: Mountain entered the battlefield...\"," +
                        "\n  \"total_length\": 5234,\n  \"truncated\": true,\n  \"cursor\": 5234\n}"),
                example("Cursor delta",
                        "{\n  \"log\": \"Player2 casts Swords to Plowshares targeting Goblin Guide.\"," +
                        "\n  \"total_length\": 5301,\n  \"truncated\": false,\n  \"cursor\": 5301\n}"));
    }

    @Override public Map<String, Object> execute(JsonObject arguments, BridgeCallbackHandler handler) {
        int maxChars = getInt(arguments, "max_chars", 0);
        Integer cursor = getIntOrNull(arguments, "cursor");
        return handler.getGameLogChunk(maxChars, cursor);
    }
}
