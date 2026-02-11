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

## LLM cost analysis

```bash
# Cost per player
for f in "$GAME_DIR"/*_cost.json; do echo "$(basename "$f" _cost.json): $(cat "$f")"; done
```

## Blocking and combat issues

```bash
# Find empty-choices GAME_TARGET events (e.g. blocker assignment bugs)
grep -c "Select attacker to block" "$GAME_DIR"/*_pilot.log

# Look for repeated GAME_TARGET patterns in LLM logs
jq -r 'select(.type=="tool_result") | select(.result | contains("Select attacker to block"))' "$GAME_DIR"/*_llm.jsonl | head -5
```

## Priority desync detection

When a player is desynced, `pass_priority` returns timeout but the game is progressing for others.

```bash
# Count consecutive pass_priority timeouts (desync signature)
jq -r 'select(.type=="tool_result") | select(.result | contains("timeout")) | .ts' "$GAME_DIR"/*_llm.jsonl | head -20

# Cross-reference: does get_game_state show game progressing while pass_priority times out?
# Look for changing turn numbers in game_state responses during timeout periods
jq -r 'select(.type=="tool_result") | select(.tool=="get_game_state") | .result' "$GAME_DIR"/*_llm.jsonl | jq -r '.turn' | uniq -c
```

## Context trimming pressure

```bash
# Count context_trim events per player (high counts = LLM was looping)
jq -r 'select(.type=="context_trim")' "$GAME_DIR"/*_llm.jsonl | wc -l

# Check rendered_size after trims (should be ~62 when trimming is active)
jq -r 'select(.type=="context_trim") | .rendered_size' "$GAME_DIR"/*_llm.jsonl | sort -n | uniq -c
```

## Mana payment errors

```bash
# Find GAME_CHOOSE_ABILITY errors (dual land mana selection)
grep "GAME_CHOOSE_ABILITY" "$GAME_DIR"/*_errors.log

# Find GAME_PLAY_MANA answer=true rejections
grep "choose mana source/pool" "$GAME_DIR"/*_errors.log
```
