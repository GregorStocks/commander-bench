# Create Issues for Game Problems

Analyze the most recent game log and create issue files for every problem found.

## Workflow

1. Find the most recent game log:
   ```bash
   GAME_DIR=$(readlink ~/mage-bench-logs/last || ls -dt ~/mage-bench-logs/game_* | head -1)
   echo "Analyzing: $GAME_DIR"
   ```

2. Read all relevant log files from that directory:
   - `errors.log` — global errors
   - `*_errors.log` — per-player errors
   - `*_pilot.log` — per-player LLM decision logs
   - `game_events.jsonl` — game event stream
   - `config.json` — game configuration (players, models, decks)
   - `*_skeleton.jsonl` — raw MCP tool call logs

3. Analyze for problems across these categories:
   - **MCP/tooling bugs**: wrong tool behavior, crashes, index errors
   - **LLM decision failures**: models misusing tools, sending wrong parameters, loops
   - **Game flow issues**: infinite loops, stalling, missing events, auto-pass triggers
   - **Prompt/instruction gaps**: models not understanding how to use tools correctly
   - **Context/resource issues**: context trimming, token waste, excessive polling

4. For each problem found, create a JSON issue file in `issues/`:
   ```json
   {
     "title": "Short summary",
     "description": "Full description with evidence from logs.\n\nGame log: ~/mage-bench-logs/game_YYYYMMDD_HHMMSS/",
     "status": "open",
     "priority": N,
     "type": "task",
     "labels": ["relevant-labels"],
     "created_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00",
     "updated_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00"
   }
   ```

   Priority guide:
   - **P1**: Bugs that break core functionality (spells fizzle, actions cancelled)
   - **P2**: Bugs that cause major waste (infinite loops, stalling, repeated errors)
   - **P3**: Suboptimal behavior or missing features (passive play, bad targeting, missing events)
   - **P4**: Minor issues (transient API errors, cosmetic)

   Labels: `headless-client`, `puppeteer`, `pilot`, `streaming-client`

   Filename: kebab-case summary (e.g., `mana-pool-loop.json`)

5. **Always include the game log path** in each issue description so we can find the evidence later.

6. Check existing issues first (`ls issues/`) to avoid duplicates. If an existing issue covers the same problem, skip it or note it's a repeat occurrence.

7. Present a summary of all issues created, grouped by priority.
