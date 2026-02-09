# Logging

All game logs live in `~/commander-bench-logs/game_YYYYMMDD_HHMMSS/`.

## Per-player log files

| File pattern | Source | Contents |
|---|---|---|
| `{name}_pilot.log` | `pilot.py` stdout | LLM reasoning, tool calls, game actions, errors |
| `{name}_llm.log` | `chatterbox.py` stdout | LLM chat commentary, tool calls, errors |
| `{name}_mcp.log` | `sleepwalker.py` / potato stdout | Auto-play actions, game log excerpts |
| `{name}_errors.log` | Python `_log_error()` + Java `logError()` | Errors only (written in real-time from both sides) |
| `{name}_cost.txt` | `llm_cost.py` | Cumulative LLM API cost in USD |

## Aggregated files

| File | Created by | Contents |
|---|---|---|
| `errors.log` | `harness.py` `_write_error_log()` | All `*_errors.log` files concatenated, prefixed with source |
| `observer.log` | Observer/streaming client | Game creation, table setup, recording |
| `config.json` | `harness.py` | Copy of the game config used |

## Error logging

Errors are written at the source, not pattern-matched after the fact:

- **Python side**: `_log_error(game_dir, username, msg)` in `pilot.py` and `chatterbox.py` appends to `{name}_errors.log` and prints to stdout.
- **Java side**: `SkeletonCallbackHandler.logError(msg)` appends to the same file. Triggered when `chooseAction()` returns `success: false` or `McpServer` catches an unhandled exception. The file path is passed via `-Dxmage.headless.errorlog=...`.

Both Python and Java write to the same per-player error file, so errors appear in chronological order regardless of layer.

## What counts as an error

- `choose_action` returning `success: false` (wrong args, index out of range, etc.)
- LLM API errors (timeouts, 402/404, transient failures)
- LLM degradation (10+ consecutive empty responses)
- Stall detection (8+ LLM turns without a successful game action)
- Context trimming (informational but useful for debugging)
- MCP request exceptions (unhandled errors in Java tool handlers)

## Timestamps

All Python log lines use `[HH:MM:SS]` prefix (via `_log()` helper). Java uses the same format via log4j `[HH:mm:ss]` pattern. Error log entries include timestamps from whichever side wrote them.
