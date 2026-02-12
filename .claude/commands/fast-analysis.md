# Fast Game Analysis

Quickly analyze a game using only the exported `.json.gz` file. This covers ~85-90% of what the full analysis finds — game narrative, LLM decision quality, error patterns, bug identification — without needing the raw log directory.

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

### Step 2: Find or generate the gz file

```bash
GAME_ID=game_YYYYMMDD_HHMMSS  # from step 1
GZ_PATH=website/public/games/${GAME_ID}.json.gz
```

a. Check if `$GZ_PATH` exists on the current branch.
b. If not, check if `~/mage-bench-logs/${GAME_ID}/game_events.jsonl` exists. If so, generate the gz:
   ```bash
   uv run python scripts/export_game.py ${GAME_ID}
   ```
   This creates `website/public/games/${GAME_ID}.json.gz`.
c. If neither exists, tell the user and stop.

### Step 3: Extract game overview

Decompress the gz to a temp file, then extract overview fields:
```bash
gunzip -k -c $GZ_PATH > /tmp/game_analysis.json
python3 -c "
import json
d = json.load(open('/tmp/game_analysis.json'))
print(f'Game: {d[\"id\"]}')
print(f'Format: {d.get(\"deckType\",\"?\")} ({d.get(\"gameType\",\"?\")})')
print(f'Turns: {d[\"totalTurns\"]}')
print(f'Winner: {d[\"winner\"]}')
for p in d['players']:
    print(f'  {p[\"name\"]} ({p.get(\"model\",\"?\")}) - cost: \${p.get(\"totalCostUsd\",0):.2f} - placement: {p.get(\"placement\",\"?\")}')
"
```

### Step 4: Reconstruct the game narrative

Extract turn boundaries and key actions:
```bash
python3 -c "
import json
d = json.load(open('/tmp/game_analysis.json'))
snapshots = d['snapshots']
actions = d['actions']

# Turn-boundary snapshots
seen = set()
for s in snapshots:
    turn = s.get('turn', 0)
    if turn not in seen:
        seen.add(turn)
        info = []
        for p in s.get('players', []):
            bf = [c.get('name','?') for c in p.get('battlefield', [])]
            info.append(f'{p[\"name\"]}: {p.get(\"life\",\"?\")}hp hand={p.get(\"hand_count\", len(p.get(\"hand\",[])))}'
                + (f' bf=[{\", \".join(bf)}]' if bf else ''))
        print(f'Turn {turn}: {\" | \".join(info)}')

print()
# Key actions
for a in actions:
    msg = a.get('message', '')
    dominated = any(x in msg.lower() for x in ['plays', 'casts', 'attacks', 'blocks', 'damage',
        'destroys', 'dies', 'mulligans', 'wins', 'lost', 'activates', 'targets', 'sacrifices'])
    if dominated or a.get('type') == 'chat':
        prefix = '[CHAT] ' if a.get('type') == 'chat' else ''
        print(f'  {prefix}{msg[:200]}')
" 2>&1 | head -100
```

### Step 5: Analyze LLM events and errors

```bash
python3 -c "
import json
from collections import Counter
d = json.load(open('/tmp/game_analysis.json'))
events = d.get('llmEvents', [])

# Event type counts
types = Counter(e.get('type','?') for e in events)
print('=== LLM Event Types ===')
for t, c in types.most_common(): print(f'  {t}: {c}')

# By player
print()
for player in set(e.get('player','?') for e in events):
    pe = [e for e in events if e.get('player') == player]
    pt = Counter(e.get('type','?') for e in pe)
    print(f'{player}: {dict(pt.most_common())}')

# Failed tool calls
print()
print('=== Failed Tool Calls ===')
for tc in events:
    if tc.get('type') != 'tool_call': continue
    result = str(tc.get('result', ''))
    if any(x in result.lower() for x in ['error', 'out of range', 'required', 'invalid', 'failed']):
        print(f'  {tc.get(\"player\",\"?\")} | {tc.get(\"tool\",\"?\")} | args={json.dumps(tc.get(\"args\",{}))} | {result[:200]}')

# Stalls, resets, auto-pilot, errors
print()
for t in ['stall', 'context_reset', 'auto_pilot_mode', 'llm_error']:
    evts = [e for e in events if e.get('type') == t]
    if evts: print(f'{t}: {len(evts)} events')

# Token/cost summary
responses = [e for e in events if e.get('type') == 'llm_response' and e.get('usage')]
print()
for player in set(e.get('player','?') for e in responses):
    pr = [e for e in responses if e.get('player') == player]
    pt = sum(e['usage'].get('promptTokens',0) for e in pr)
    ct = sum(e['usage'].get('completionTokens',0) for e in pr)
    print(f'{player}: {len(pr)} responses, {pt:,} prompt, {ct:,} completion tokens')
"
```

### Step 6: Sample LLM reasoning

Extract a few reasoning samples from each player to assess decision quality (mulligan, combat, spell targeting):
```bash
python3 -c "
import json
d = json.load(open('/tmp/game_analysis.json'))
events = d.get('llmEvents', [])
for player in set(e.get('player','?') for e in events):
    print(f'=== {player} ===')
    count = 0
    for e in events:
        if e.get('type') == 'llm_response' and e.get('player') == player:
            r = e.get('reasoning', '')
            if len(r) > 50:
                print(f'--- Sample {count+1} ---')
                print(r[:600])
                print()
                count += 1
                if count >= 4: break
" 2>&1 | head -80
```

### Step 7: Check existing issues and file new ones

```bash
for f in issues/*.json; do echo "$(basename "$f" .json): $(python3 -c "import json; print(json.load(open('$f'))['title'])")"; done
```

For each **code bug** found (not model behavior issues), create an issue in `issues/`:

```json
{
  "title": "Short summary",
  "description": "Description with evidence from gz analysis.\n\nEvidence:\n- game {game_id}: [error pattern description]\n- llmEvents tool_call failures: [count and pattern]\n\nSuggested fix: ...",
  "status": "open",
  "priority": N,
  "type": "task",
  "labels": ["relevant-labels"],
  "created_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00",
  "updated_at": "YYYY-MM-DDTHH:MM:SS.000000-08:00"
}
```

Priority: P1 = crashes/broken actions, P2 = loops/stalling/repeated errors, P3 = bad tool descriptions/missing features, P4 = minor/cosmetic.

Labels: `headless-client`, `puppeteer`, `pilot`, `streaming-client`

### Step 8: Present summary

Summarize findings: game outcome, key plays, LLM quality assessment, bugs found (with issue filenames), and any model-only issues noted.

## What this skill does NOT do

- Read raw pilot logs, bridge logs, error logs, or server logs
- Trace bugs to specific source code lines
- Create debugging scripts in `scripts/debugging/`
- Update `doc/investigating-game-logs.md`

For deeper analysis with source code tracing, use `/analyze-game` instead.
