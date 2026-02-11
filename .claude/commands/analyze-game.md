# Analyze Game Logs and File Issues

Analyze recent game logs, identify bugs and problems, and file issues for each one.

## Workflow

1. Find recent game logs:
   ```bash
   GAME_DIR=$(readlink ~/mage-bench-logs/last-llm4 || ls -dt ~/mage-bench-logs/game_* | head -1)
   echo "Analyzing: $GAME_DIR"
   ls "$GAME_DIR"
   ```

2. Read `config.json` and `game_meta.json` first — understand who played, what models/decks were used, and the game outcome (winner, turn count, life totals).

3. Check existing issues to avoid duplicates:
   ```bash
   for f in issues/*.json; do echo "$(basename "$f" .json): $(jq -r .title "$f")"; done
   ```

4. **Use parallel agents** to analyze different log types simultaneously.

   **Pay particular attention to chat messages** — they're the most human-readable signal about what went wrong. XMage sends game-state information, error messages, and rule explanations through chat. Players also chat when confused or stuck. Always read chat messages early and use them to guide your investigation of other log files.

   - **Chat messages**: Extract `player_chat` events from `game_events.jsonl` (`jq 'select(.type=="player_chat")' game_events.jsonl`). Look for XMage system messages about illegal actions, failed spell resolutions, mana payment problems, and rule enforcement. Look for player messages that reveal confusion or frustration. Chat messages often point directly at the root cause before you even open error logs.
   - **Error logs**: Read `*_errors.log` files. Look for Java exceptions (NPE, IndexOutOfBounds, ClassCast), MCP tool failures, and stack traces. Note the exact filename and line numbers.
   - **Pilot logs**: Read `*_pilot.log` files. Look for LLM decision failures, repeated tool call patterns (loops), models sending wrong parameters, empty responses, and context trimming warnings.
   - **Bridge logs**: Read `*_bridge.jsonl` files. Look for repeated identical MCP calls (loop signatures), failed actions, "Index out of range" errors, and action sequences that suggest confusion (e.g., cast → cancel → cast → cancel).
   - **Game events**: Read `game_events.jsonl`. Look for stalls (long gaps between events), excessive auto-passes, turn timeouts, and game flow anomalies.

5. **Cross-reference findings** — a single bug often shows up across multiple log files. For example, an NPE in error logs corresponds to a failed tool call in bridge logs and a confused retry loop in pilot logs. Group these into one issue, not three.

6. **Distinguish code bugs from model issues**:
   - **Code bugs** (file issues): NPEs, wrong tool behavior, missing error handling, incorrect game state reporting — these need code fixes in Java or Python.
   - **Model behavior** (note but don't file unless extreme): Passive play, bad threat assessment, suboptimal targeting — these are model quality issues. Only file if a model is completely non-functional (e.g., never plays spells, always passes).
   - **Already handled** (skip): Transient API errors with successful retries, empty responses caught by retry logic, one-off mistakes the model recovers from.

7. For each code bug, **trace it to source code**. Read the relevant Java/Python files to identify the exact line and root cause. Include in the issue:
   - The game log path: `~/mage-bench-logs/game_YYYYMMDD_HHMMSS/`
   - Specific log files and approximate line numbers where the bug manifests
   - The source code file and line where the fix should go (e.g., `BridgeCallbackHandler.java:1407`)
   - A brief description of the root cause and suggested fix direction

8. Create issue files in `issues/`:
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

9. **Focus on a single game.** Only analyze the most recent game found in step 1. Do not look at older games — each analysis run should be scoped to one game to keep context focused and output actionable.

10. Present a summary of all issues created, grouped by priority. For model-only issues, mention them in the summary but note they don't need code fixes.

11. **Document useful log investigation tricks.** If you discovered any useful jq queries, grep patterns, cross-referencing techniques, or other tricks for investigating game logs during this analysis, append them to `doc/investigating-game-logs.md`. Don't duplicate what's already there — read the file first. This builds up a shared knowledge base over time.
