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

## Blunder Pattern Reference

Concrete patterns to look for when scanning decisions. Organized by category with detection heuristics and examples from real games.

### `unused_mana` — Had mana and playable cards but didn't use them

The most common LLM blunder. Models frequently pass priority with a hand full of castable spells for no discernible reason.

**Missing land drops.** Player has a land in hand, it's their main phase, and they haven't played a land this turn — but they pass without playing it. Almost never correct. Even if the land seems unnecessary now, it provides future mana and deck thinning. Detection: check `land_drops_used: 0` in `get_action_choices` result while player's hand contains a land (visible in the snapshot).

**Holding castable creatures/spells for no reason.** Player has mana, has a creature or sorcery in hand, and passes both main phases without casting anything. No flash value, no counterspell backup, no reason to wait. If the choice list includes castable spells and the player chose `pass_priority`, investigate why. Major if it happens multiple turns in a row.

**Not activating available abilities.** Equipment on the battlefield but never equipped. Planeswalker in play but no loyalty ability used. Creature with a relevant activated ability sitting idle. If the board has activatable permanents and the player has mana, passing is suspicious.

**Not using mana sinks at end of turn.** Player passes with mana up and has instant-speed abilities or cards with flash. End of opponent's turn is free mana — using it costs nothing. Look for this in the opponent's end step.

### `wasted_resources` — Played spells with no meaningful impact

The spell resolved (or tried to) but accomplished nothing, or actively hurt the caster.

**Color-restricted spells on wrong color.** Pyroblast targeting a non-blue spell or permanent — it resolves but the "destroy/counter if blue" clause does nothing. Same for Hydroblast on non-red. The spell is technically legal to cast but accomplishes zero. Detection: look for Pyroblast/Red Elemental Blast choices where the target's color identity isn't blue, or Hydroblast/Blue Elemental Blast where the target isn't red.

**Casting into Chalice of the Void.** Playing a spell whose mana value matches Chalice's charge counters. The spell gets auto-countered for free. This is a major blunder because Chalice is public information — it's on the battlefield, visible to everyone. Detection: check if Chalice is on opponent's battlefield in the snapshot, note its counters, then check if the cast spell's mana value matches.

**Show and Tell with nothing to cheat in.** Casting Show and Tell (each player puts a permanent from hand onto the battlefield) when your hand has no impactful permanent to put in, or only has cards cheaper than 3 mana anyway. Opponent gets a free deployment while you waste 3 mana. Detection: when Show and Tell is cast, check the caster's hand for expensive permanents worth cheating in.

**Mox Diamond with no land to discard.** Mox Diamond requires discarding a land as it enters. With no land in hand, it enters and immediately goes to the graveyard — pure card disadvantage. Detection: casting Mox Diamond when hand contains zero lands.

**Countering your own spells.** Using Daze, Force of Will, or any counterspell targeting your own spell on the stack. Models sometimes get confused about valid targets. This is always a major blunder. Detection: counterspell choice where the target is the caster's own spell (check stack contents in snapshot).

**Self-targeting removal.** Using Bitter Triumph, Swords to Plowshares, or similar removal on your own creature instead of an opponent's. Check whether the target permanent is controlled by the caster or the opponent.

**Casting a duplicate legendary.** Playing a second copy of a legendary permanent you already control, losing one to the legend rule for zero value. The new copy enters then one is immediately sacrificed. Detection: check battlefield for a permanent with the same name as the one being cast.

**Activating irrelevant abilities.** Shadowspear's "opponents' permanents lose hexproof and indestructible" when no opponent permanent has hexproof or indestructible. Paying mana for literally nothing. Detection: ability activation where the effect has no valid targets or no applicable game state.

**Engineered Explosives for wrong permanent type.** EE destroys nonland permanents with mana value equal to charge counters. Casting it at X=0 to hit a land does nothing — lands aren't nonland permanents. Detection: check what the player says they're trying to destroy vs. what EE actually does.

**Formidable/ETB triggers declined for no reason.** Declining a "may" ability that's pure upside (e.g. Formidable Speaker's free tutor). If the choice is "use this free ability?" and the answer is no with no downside, that's wasted value.

### `wrong_target` — Removed or targeted the wrong thing

A spell or ability was used on a legal target, but a much better target was available.

**Wasteland on a basic land.** Wasteland's ability says "destroy target nonbasic land." It can't target basics. If the model tries to activate Wasteland targeting an opponent's basic Mountain/Island/etc., the ability either fizzles or targets something useless. Detection: Wasteland activation where the target is a basic land.

**Fetching the wrong land type.** Cracking Scalding Tarn for a Taiga (R/G) while explicitly needing blue mana for spells in hand. Or fetching a dual that provides colors you already have surplus of while lacking colors you need. Detection: compare the fetched land's colors to the spells in hand and the mana already available.

**Removing the irrelevant creature.** Using premium removal on a 1/1 token while a 5/5 game-winning threat goes unanswered. Or killing a creature with a death trigger that benefits the opponent. Detection: compare the removed creature's stats/abilities to other creatures on the opponent's board.

**Giving opponents resources.** Kozilek's Command in a mode that targets an opponent for card draw. Targeting wrong player with beneficial effects. Detection: any choice where a beneficial effect targets an opponent.

**Naming the wrong card with Pithing Needle/Surgical Extraction.** Shutting down an irrelevant activated ability while a dangerous one (like a combo piece) goes unchecked. Context-dependent — requires understanding what abilities matter in the matchup.

**Exiling the wrong card from opponent's hand.** With Thought-Knot Seer or Thoughtseize, taking a cantrip instead of a bomb, or taking a creature instead of the removal spell that answers your board. Requires evaluating which card in opponent's hand is most dangerous.

### `missed_lethal` — Could have won but didn't

The most clear-cut blunder category. Player had a line to win the game and didn't take it.

**Not attacking with lethal on board.** Opponent at 5 life, player has 6+ power of creatures on board, but either attacks with only some of them or doesn't attack at all. Detection: sum up the power of the player's untapped creatures and compare to opponent's life total minus their possible blocks.

**Missing combo completion.** Having all pieces of a combo assembled and not activating it. Classic example: Dark Depths (legendary land with ice counters) + Thespian's Stage on the battlefield. Activating Stage to copy Depths creates a 20/20 indestructible flying Marit Lage token. Having both in play and not doing this is a major blunder. Detection: check battlefield for known combo pairs (Depths+Stage, Kiki-Jiki+Pestermite, etc.).

**Burn spells in hand with opponent at low life.** Opponent at 3 life, player has Lightning Bolt in hand, but casts a creature instead. Unless the creature has haste and deals more damage, just Bolt them. Detection: compare burn spell damage to opponent's life total.

**Skipping attacks entirely.** Having creatures on board, opponent has no blockers or insufficient blockers, and player enters end step without declaring attackers. Often happens when the model gets confused about the combat phase. Detection: player has untapped creatures, combat phase is available, but no attacks were made (check subsequent_actions for absence of attack declarations).

### `strategic_error` — Fundamentally wrong game plan

Broader mistakes about strategy, game plan, or card evaluation that show the model doesn't understand what matters.

**Not activating Thespian's Stage on Dark Depths.** This is worth calling out specifically because it's one of the most egregious combo misses. The line is: pay 2, activate Stage copying Depths. The copy enters with 0 ice counters, triggers immediately, sacrifice it, get Marit Lage (20/20 flying indestructible). Having both on board for multiple turns without doing this is a major strategic error.

**Choosing to draw instead of play with an aggro deck.** Aggressive decks almost always want to be on the play for tempo advantage. Choosing to draw means giving the opponent an extra turn to set up. Only makes sense in very specific matchups.

**Drawing cards at critical life totals.** Using Abzan Charm's "draw 2, lose 2 life" mode when at 5 life with a threatening board to deal with. The removal mode (exile creature with power 3+) would answer a threat AND avoid losing 2 life. Detection: card mode choice where one mode costs life and the player is at a precarious life total.

**Failing to use available counterspells.** Having Force of Will or Metallic Rebuke in hand with mana/cards to cast it, but letting an opponent's game-winning threat resolve. If the reasoning says "I'll save it for later" but there's nothing more threatening coming, that's a strategic error.

**Using Karn to animate your own key artifacts.** Karn's +1 turns an artifact into a creature, making it vulnerable to creature removal. Animating a critical mana source (like Candelabra of Tawnos) or a combo piece into a fragile 1/1 is a liability, not a benefit.

**Paying life unnecessarily.** Shocking yourself (2 life for untapped dual land) on turn 1 with no 1-mana spell to cast. Or fetching + shocking when you could wait a turn and the life point matters in the matchup (especially against aggro/burn).

**Failing to play around known information.** After Thoughtseizing an opponent and seeing their hand, making plays that walk into the cards you know they have. The whole point of hand disruption is to plan around what you saw.

**Cancelling a search without selecting.** Urza's Saga chapter III, or any tutor effect where you search your library — cancelling the search and getting nothing when there were valid targets. This wastes the entire permanent/spell for zero value.

### `bad_sequencing` — Wrong order of plays

The individual plays might be fine, but the order is wrong, losing value or creating problems.

**Cantrip after land drop.** Playing your land for the turn, then casting Ponder/Brainstorm/Preordain. Should always cantrip first — you might find a better land, or find a fetchland that gives shuffle equity with Brainstorm. Major in Legacy/Vintage where cantrip sequencing is fundamental.

**Creatures before combat with tricks in hand.** Deploying a creature in precombat main phase when you have combat tricks or pump spells. Opponent now knows your board state before blocks. Should attack first (keeping them guessing), then deploy post-combat. Exception: if the creature has haste or an ETB that matters for combat.

**Bounce land on turn 1 as only land.** Playing Dimir Aqueduct / Simic Growth Chamber / etc. as your only land. Bounce lands require returning a land to your hand on ETB. With no other land to bounce, you return itself — net zero mana, wasted turn. Detection: bounce land played with `battlefield` showing no other lands.

**Spells before lands.** Casting artifacts or spells before playing a land, then being unable to play the land or missing triggers (landfall, etc.). Should almost always play the land first to maximize available mana. Exception: artifact mana that enables playing a tapland and still having mana up.

**Sunblast Angel after attacking.** Casting Sunblast Angel in postcombat main after attacking with all your creatures. Angel's ETB destroys all tapped creatures — including your own attackers. Should either cast Angel precombat (destroying opponent's tapped creatures) or not attack with everything.

**Postcombat Thoughtseize.** Casting Thoughtseize/Duress postcombat when you could have cast it precombat to see the opponent's hand and make informed attack decisions. On turn 1 this doesn't matter, but later when attacks are relevant, information before combat is valuable.

**Fetching at the wrong time.** Cracking a fetchland immediately when you could hold it for shuffle equity after Brainstorm, or waiting to fetch when you need the mana right now for a critical spell. Context-dependent but often a sequencing issue.

### `bad_combat` — Poor attack/block decisions

**Not blocking when correct.** Taking damage from a small creature when you have a blocker that would survive or trade favorably. If you're at 12 life and a 2/2 attacks, blocking with your 3/3 is usually correct unless you need to attack back with it next turn.

**Blocking tramplers with small creatures.** Putting a 1/1 in front of a 5/5 trample to save 1 point of damage while losing the creature. Only correct if the 1/1 has deathtouch or you're specifically at exactly that 1 life point.

**Ignoring summoning sickness (wrong).** The model claims a creature has summoning sickness when it's been on the battlefield since the player's last turn start (or has haste), then doesn't attack with it. Check the snapshot — if the creature entered before this turn and doesn't have a reason to be unable to attack, the model is confused.

**Not using deathtouch strategically.** Having a deathtoucher and blocking/being blocked by a small creature instead of trading up against the biggest threat. Deathtouch creatures should almost always block the largest attacker.

**Sending only part of a lethal force.** Having 10 power on board against an opponent at 7 life with one 3/3 blocker. Attacking with everything forces the bad block; attacking with only your biggest creature lets them block and survive. Alpha strike when the math checks out.

**Attacking into a guaranteed bad trade for no reason.** Swinging your only 2/2 into a 3/3 with no trick available. You lose your creature, they keep theirs. Unless you're pressuring a planeswalker or have no other plan, just don't attack.

### `walked_into_removal` — Played into obvious board wipe or removal

**Overextending into board wipes.** Casting multiple creatures when you already have a reasonable board and the opponent has 4+ mana open in colors that have board wipes (W for Wrath of God, WW for Supreme Verdict, BB for Toxic Deluge). Holding back even one threat means you recover faster post-wipe. Hard to detect automatically — requires estimating what removal the opponent might have — but flagrant cases (deploying 3 creatures into known Wrath colors with mana up) are clear.

**Playing your best threat into open counter mana.** Casting Emrakul into UU with an opponent who hasn't tapped out all game and is clearly representing Counterspell. Sometimes you have to run into it, but if you have a less important spell to bait with first, you should.

**Casting a duplicate legendary permanent.** Playing a second Ragavan when you already control one. You lose one to the legend rule for zero value. Detection: casting a legendary permanent when one with the same name is already on your battlefield.

**Stacking buffs on one creature.** Putting multiple equipment or auras on a single creature, making it a massive blowout when they use a single removal spell. Spread your buffs unless you have protection/hexproof to back it up.

### General Detection Heuristics

When scanning decisions, look for these red flags across all categories:

1. **`pass_priority` when `has_playable_cards: true`** — the model passed with things to do. Check why.
2. **Reasoning that contradicts the board state** — model says "I have no creatures" when the snapshot shows creatures. Or "opponent has hexproof" when nothing has hexproof.
3. **Reasoning that shows rules confusion** — model thinks a card does something it doesn't, or misunderstands a keyword.
4. **Multiple consecutive passes** — if the model passes 3+ times in the same turn with a full hand, something is wrong.
5. **`choice_count: 2+` with forced-looking choices** — if there are many options but the model picks the only one that accomplishes nothing, investigate.
6. **Self-targeting in `subsequent_actions`** — actions that say the player targeted their own permanent with removal or countered their own spell.
7. **Same mistake repeated** — casting into Chalice twice, missing land drops three turns in a row. Repeated blunders should each be annotated but with a note that it's a recurring pattern.
