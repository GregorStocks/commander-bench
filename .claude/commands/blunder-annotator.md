# Blunder Annotator

Analyze LLM decisions in recent games to find blunders — moments where the model made clearly suboptimal plays. Annotate each game's `.json.gz` export so blunders are visible in the web UI.

You (Claude Code) do the analysis yourself using your knowledge of Magic: The Gathering strategy. No OpenRouter API calls.

## Workflow

### Step 1: Find un-annotated games

Find games that don't have blunder annotations yet. Run this to check:

```bash
uv run python -c "
import gzip, json, glob, sys
for gz in sorted(glob.glob('website/public/games/game_*.json.gz')):
    with gzip.open(gz, 'rt') as f:
        data = json.load(f)
    if 'annotations' not in data:
        print(gz)
"
```

Pick up to **5** un-annotated games from the output. If there are fewer than 5, use all of them. If there are none, tell the user all games are already annotated.

If the user specified a particular game, use that instead regardless of annotation status.

### Step 2: Process each game

For each game, run through Steps 3–7 below. Keep analysis concise to avoid context pressure — don't dump full decision lists, just identify the blunders.

### Step 3: Get game context

```bash
GAME_ID=game_YYYYMMDD_HHMMSS
GZ_PATH=website/public/games/${GAME_ID}.json.gz
uv run python scripts/analysis/game_overview.py $GZ_PATH
```

Read the overview. Understand who won, the format, and the decks.

### Step 4: Extract and review decisions

```bash
uv run python scripts/analysis/extract_decisions.py $GZ_PATH
```

Read through the decisions. Focus on ones where:
- `choice_count >= 2` (multiple options available)
- `is_forced` is false
- The reasoning shows a misunderstanding of the board state
- The subsequent actions suggest the choice led to a bad outcome

**Do NOT paste the full decision list into your response.** Scan it internally and only note the blunders.

### Step 5: Identify blunders

For each blunder, determine:

- **severity**: `minor` (slightly suboptimal), `moderate` (clear mistake with consequences), `major` (game-losing or massive value loss)
- **category**: one of:
  - `missed_lethal` — could have won but didn't
  - `walked_into_removal` — played into obvious board wipe or removal
  - `bad_sequencing` — wrong order of plays (e.g. attack before playing land)
  - `bad_combat` — poor attack/block decisions losing value
  - `wasted_resources` — played spells with no meaningful impact
  - `wrong_target` — removed or targeted the wrong thing
  - `unused_mana` — had mana and playable cards but didn't use them
  - `strategic_error` — fundamentally wrong game plan
- **description**: What went wrong, in concrete game terms
- **llmReasoning**: Why the LLM probably made this mistake (reference their reasoning text)
- **actionTaken**: What they actually did
- **betterLine**: What they should have done instead

### Step 6: Write annotations

Create a temp file with annotations and patch the gz:

```bash
cat > /tmp/blunder_annotations.json << 'ANNOTATIONS'
[...your annotations array...]
ANNOTATIONS
uv run python scripts/analysis/annotate_game.py $GZ_PATH /tmp/blunder_annotations.json
```

### Step 7: Report

After processing all games, present a single summary table:

| Game | # Blunders | Players | Notable |
|------|-----------|---------|---------|
| game_20260211_080409 | 3 | Alice (2), Bob (1) | Alice missed lethal twice |

## Tips

- Don't flag every suboptimal play. Focus on clear, unambiguous mistakes where a better line is obvious.
- Look at the reasoning text — it often reveals *why* the LLM blundered.
- Context matters: a play that looks bad in isolation might make sense given information the LLM had. Give benefit of the doubt on close calls.
- Keep it lean. The goal is 5 games per invocation without hitting context limits.
- If a game has 50+ decisions, skim by turn range rather than reading every one.
