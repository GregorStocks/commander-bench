# Investigating Game Logs

Useful tricks for analyzing game logs, discovered during analysis runs. This file is incrementally built up — each `analyze-game` run may add new techniques.

See `logging.md` for the file format reference.

## Finding game directories

```bash
# Most recent LLM game
GAME_DIR=~/mage-bench-logs/$(readlink ~/mage-bench-logs/last-llm4)

# Most recent game by config name
GAME_DIR=~/mage-bench-logs/$(readlink ~/mage-bench-logs/last-gauntlet)

# Most recent game on a branch
GAME_DIR=~/mage-bench-logs/$(readlink ~/mage-bench-logs/last-branch-GregorStocks-my-branch)

# All recent games, newest first
ls -dt ~/mage-bench-logs/game_* | head -5
```

## Chat messages

Chat messages are the most human-readable signal. Always check them first.

```bash
# Extract all chat messages with timestamps
jq -r 'select(.type=="player_chat") | "\(.timestamp) \(.message)"' "$GAME_DIR/game_events.jsonl"
```

## Error logs

```bash
# Show all errors across all players
for f in "$GAME_DIR"/*_errors.log; do echo "=== $(basename "$f") ==="; cat "$f"; done

# Count errors per player
wc -l "$GAME_DIR"/*_errors.log
```

## Stall and loop detection

```bash
# Find stall events
grep -n "Stalled:" "$GAME_DIR"/*_pilot.log

# Find auto-pass triggers
grep -n "auto-pass\|Brain freeze\|auto_pilot_mode" "$GAME_DIR"/*_pilot.log

# Count tool calls in bridge log to spot loops (top 5 most-called tools)
jq -r 'select(.type=="tool_call") | .tool' "$GAME_DIR"/*_bridge.jsonl | sort | uniq -c | sort -rn | head -5
```

## Server connection issues

```bash
# Check for socket/connection failures
grep -i "unable to create socket\|SESSION CALLBACK EXCEPTION\|waitResponseOpen\|disconnected" "$GAME_DIR/server.log"
```

## Game duration and flow

```bash
# First and last event timestamps (game duration)
head -1 "$GAME_DIR/game_events.jsonl" | jq -r .timestamp
tail -1 "$GAME_DIR/game_events.jsonl" | jq -r .timestamp

# Turn count
jq -r 'select(.type=="turn") | "\(.timestamp) Turn \(.turn_number) - \(.active_player)"' "$GAME_DIR/game_events.jsonl" | tail -5
```
