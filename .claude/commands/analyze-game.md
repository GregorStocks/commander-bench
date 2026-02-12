# Analyze Game Logs and File Issues

Deep analysis of game logs — reads all raw log files, traces bugs to source code, and files detailed issues. For quick triage, use `/fast-analysis` instead.

## Workflow

### Step 1: Select the game

Determine which game to analyze:

- If the user specified a game ID (e.g. `game_20260211_080409`), use that.
- If the user said "most recent" or similar, find the latest:
  ```bash
  ls -la ~/mage-bench-logs/last-*
  ls -dt ~/mage-bench-logs/game_* | head -5
  ```
- If the user mentioned a config name (e.g. "standard", "gauntlet", "frontier"), use the corresponding symlink:
  ```bash
  readlink ~/mage-bench-logs/last-{config}
  ```
  where `{config}` might be `standard-gauntlet`, `standard-dumb`, `gauntlet`, `frontier`, etc. Check what symlinks exist.
- **If ambiguous** (multiple recent games, or user just said "analyze a game"), show the 3-5 most recent games with their config and players, then ask which one:
  ```bash
  for d in $(ls -dt ~/mage-bench-logs/game_* | head -5); do
    echo "$(basename $d): $(python3 -c "import json; m=json.load(open('$d/game_meta.json')); print(f'{m.get(\"config\",\"?\")} | {m.get(\"deck_type\",\"?\")} | {\" vs \".join(p[\"name\"] for p in m[\"players\"])} | winner: ???')" 2>/dev/null || echo '(no metadata)')"
  done
  ```
  Ask the user to pick one before proceeding. **Do not guess.**

Set `GAME_DIR=~/mage-bench-logs/{game_id}`.

If the full log directory doesn't exist but `website/public/games/{game_id}.json.gz` does, tell the user the full logs aren't available and offer to run a fast analysis from the gz file instead. Stop here unless the user wants the fast analysis.

### Step 2: Bootstrap from gz (if available)

If `website/public/games/${GAME_ID}.json.gz` exists (either on the current branch or generatable from the logs), extract a quick overview before diving into raw logs:

```bash
GZ_PATH=website/public/games/${GAME_ID}.json.gz
if [ ! -f "$GZ_PATH" ] && [ -f "$GAME_DIR/game_events.jsonl" ]; then
  uv run python scripts/export_game.py ${GAME_ID}
fi
if [ -f "$GZ_PATH" ]; then
  gunzip -k -c "$GZ_PATH" | python3 -c "
import json, sys
from collections import Counter
d = json.load(sys.stdin)
print(f'Game: {d[\"id\"]} | {d.get(\"deckType\",\"?\")} | {d[\"totalTurns\"]} turns | Winner: {d[\"winner\"]}')
for p in d['players']:
    print(f'  {p[\"name\"]} ({p.get(\"model\",\"?\")}) \${p.get(\"totalCostUsd\",0):.2f}')
events = d.get('llmEvents', [])
errors = [e for e in events if e.get('type')=='tool_call' and any(x in str(e.get('result','')).lower() for x in ['error','out of range','required','failed'])]
print(f'LLM events: {len(events)} | Failed tool calls: {len(errors)}')
for e in errors[:5]:
    print(f'  {e.get(\"player\",\"?\")} | {e.get(\"tool\",\"?\")} | {str(e.get(\"result\",\"\"))[:120]}')
if len(errors) > 5: print(f'  ... and {len(errors)-5} more')
"
fi
```

This gives you a roadmap — you'll know which players had errors, roughly when, and what to look for in the raw logs.

### Step 3: Read game metadata

Read `config.json` and `game_meta.json` — understand who played, what models/decks were used, and the game outcome (winner, turn count, life totals).

### Step 4: Check existing issues

```bash
for f in issues/*.json; do echo "$(basename "$f" .json): $(python3 -c "import json; print(json.load(open('$f'))['title'])")"; done
```

### Step 5: Analyze log files in parallel

**Use parallel agents** to analyze different log types simultaneously.

**Pay particular attention to chat messages** — they're the most human-readable signal about what went wrong. XMage sends game-state information, error messages, and rule explanations through chat. Players also chat when confused or stuck. Always read chat messages early and use them to guide your investigation of other log files.

- **Chat messages**: Extract `player_chat` events from `game_events.jsonl` (`jq 'select(.type=="player_chat")' game_events.jsonl`). Look for XMage system messages about illegal actions, failed spell resolutions, mana payment problems, and rule enforcement. Look for player messages that reveal confusion or frustration. Chat messages often point directly at the root cause before you even open error logs.
- **Error logs**: Read `*_errors.log` files. Look for Java exceptions (NPE, IndexOutOfBounds, ClassCast), MCP tool failures, and stack traces. Note the exact filename and line numbers.
- **Pilot logs**: Read `*_pilot.log` files. Look for LLM decision failures, repeated tool call patterns (loops), models sending wrong parameters, empty responses, and context trimming warnings.
- **Bridge logs**: Read `*_bridge.jsonl` files. Look for repeated identical MCP calls (loop signatures), failed actions, "Index out of range" errors, and action sequences that suggest confusion (e.g., cast → cancel → cast → cancel).
- **Game events**: Read `game_events.jsonl`. Look for stalls (long gaps between events), excessive auto-passes, turn timeouts, and game flow anomalies.

### Step 6: Cross-reference findings

A single bug often shows up across multiple log files. For example, an NPE in error logs corresponds to a failed tool call in bridge logs and a confused retry loop in pilot logs. Group these into one issue, not three.

### Step 7: Distinguish code bugs from model issues

- **Code bugs** (file issues): NPEs, wrong tool behavior, missing error handling, incorrect game state reporting — these need code fixes in Java or Python.
- **Model behavior** (note but don't file unless extreme): Passive play, bad threat assessment, suboptimal targeting — these are model quality issues. Only file if a model is completely non-functional (e.g., never plays spells, always passes).
- **Already handled** (skip): Transient API errors with successful retries, empty responses caught by retry logic, one-off mistakes the model recovers from.

### Step 8: Trace bugs to source code

For each code bug, read the relevant Java/Python files to identify the exact line and root cause. Include in the issue:
- The game log path: `~/mage-bench-logs/game_YYYYMMDD_HHMMSS/`
- Specific log files and approximate line numbers where the bug manifests
- The source code file and line where the fix should go (e.g., `BridgeCallbackHandler.java:1407`)
- A brief description of the root cause and suggested fix direction

### Step 9: Create issue files

Create issue files in `issues/`:
```json
{
  "title": "Short summary",
  "description": "Full description with root cause analysis.\n\nEvidence:\n- ~/mage-bench-logs/game_.../Player_errors.log: NPE at line 42\n- ~/mage-bench-logs/game_.../Player_bridge.jsonl: repeated cast-cancel pattern\n\nSource: BridgeCallbackHandler.java:1407 — cv.getDisplayName() returns null\n\nSuggested fix: null-guard displayName before passing to StringBuilder",
  "status": "open",
  "priority": N,
  "type": "task",
  "labels": ["relevant-labels"],
  "created_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00",
  "updated_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00"
}
```

Priority guide:
- **P1**: Crashes or bugs that break core game actions (NPEs during targeting, spells fizzling due to code bugs)
- **P2**: Bugs causing major waste (infinite loops, stalling, repeated errors that block a player)
- **P3**: Suboptimal tool behavior or missing features (bad descriptions, missing info in prompts)
- **P4**: Minor issues (cosmetic, transient, or rare edge cases)

Labels: `headless-client`, `puppeteer`, `pilot`, `streaming-client`

### Step 10: Focus on a single game

Only analyze the game selected in step 1. Do not look at older games — each analysis run should be scoped to one game to keep context focused and output actionable.

### Step 11: Present summary

Present a summary of all issues created, grouped by priority. For model-only issues, mention them in the summary but note they don't need code fixes.

### Step 12: Create reusable debugging scripts

If you need to write any non-trivial log analysis logic (more than a simple jq one-liner), create a Python script in `scripts/debugging/` rather than writing throwaway one-off code. Run these scripts with `uv run python scripts/debugging/your_script.py`. These scripts accumulate over time into a reusable debugging toolkit. Check what already exists in `scripts/debugging/` before creating something new — you may be able to reuse or extend an existing script.

### Step 13: Document investigation tricks

If you discovered any useful jq queries, grep patterns, cross-referencing techniques, or other tricks for investigating game logs during this analysis, append them to `doc/investigating-game-logs.md`. Don't duplicate what's already there — read the file first.
