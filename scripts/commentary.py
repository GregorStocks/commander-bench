#!/usr/bin/env python3
"""Generate AI play-by-play commentary for a completed game."""

import argparse
import asyncio
import html
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from openai import AsyncOpenAI

from puppeteer.llm_cost import (
    DEFAULT_BASE_URL,
    get_model_price,
    load_prices,
    required_api_key_env,
)

LOGS_DIR = Path.home() / ".mage-bench" / "logs"
DEFAULT_MODEL = "google/gemini-2.5-flash"
MAX_COMMENTARY_TOKENS = 4096

FONT_TAG_RE = re.compile(r"<font[^>]*>|</font>")
OBJECT_ID_RE = re.compile(r"\s*\[[0-9a-f]{3,}\]")
TURN_RE = re.compile(r"^TURN (\d+) for (.+?) \(")

# Messages that are noise and should be filtered from the narrative
_NOISE_PATTERNS = [
    "draws a card",
    "skips Draw step",
    "library is shuffled",
    "skip attack",
    "has started watching",
    "puts a card from hand to the bottom",
    "from stack onto the Battlefield",
    "from hand onto the Battlefield",
]


def _strip_html(message: str) -> str:
    """Remove <font> tags and [hex_id] suffixes from action messages."""
    message = FONT_TAG_RE.sub("", message)
    message = OBJECT_ID_RE.sub("", message)
    return message.strip()


def _is_noise(action: str) -> bool:
    """Return True if this game action is routine noise not worth narrating."""
    for pattern in _NOISE_PATTERNS:
        if pattern in action:
            return True
    return False


@dataclass
class TurnEvents:
    turn_number: int
    active_player: str
    actions: list[str] = field(default_factory=list)
    reasoning: dict[str, str] = field(default_factory=dict)
    chat: list[tuple[str, str]] = field(default_factory=list)
    board_summary: str = ""


@dataclass
class GameNarrative:
    game_id: str
    game_type: str
    deck_type: str
    players: list[dict]
    winner: str | None
    turns: list[TurnEvents] = field(default_factory=list)


def _compact_board(snapshot: dict) -> str:
    """One-line board state from a state_snapshot."""
    parts = []
    for p in snapshot.get("players", []):
        name = p.get("name", "?")
        life = p.get("life", "?")
        bf = p.get("battlefield", [])
        creatures = []
        for card in bf:
            card_name = card.get("name", "?")
            pt = ""
            if card.get("power") is not None and card.get("toughness") is not None:
                pt = f" {card['power']}/{card['toughness']}"
            creatures.append(f"{card_name}{pt}")
        bf_str = ", ".join(creatures) if creatures else "empty board"
        hand_count = p.get("hand_count", len(p.get("hand", [])))
        parts.append(f"{name}: {life} life, {hand_count} cards in hand, [{bf_str}]")
    return " | ".join(parts)


def _deck_display_name(player_meta: dict) -> str | None:
    """Get a display name for a player's deck from metadata."""
    deck_path = player_meta.get("deck_path", "")
    if deck_path:
        return Path(deck_path).stem.replace("-", " ")
    return None


def load_game_narrative(game_dir: Path) -> GameNarrative:
    """Parse game logs into a structured narrative."""
    meta_path = game_dir / "game_meta.json"
    assert meta_path.exists(), f"No game_meta.json in {game_dir}"
    meta = json.loads(meta_path.read_text())

    # Build player info
    players = []
    for p in meta.get("players", []):
        players.append(
            {
                "name": p.get("name", "?"),
                "model": p.get("model", ""),
                "deck_name": _deck_display_name(p),
                "type": p.get("type", "?"),
            }
        )

    # Read events: prefer game.jsonl (merged), fall back to game_events.jsonl
    game_jsonl = game_dir / "game.jsonl"
    events_jsonl = game_dir / "game_events.jsonl"
    if game_jsonl.exists():
        source = game_jsonl
    else:
        assert events_jsonl.exists(), (
            f"No game.jsonl or game_events.jsonl in {game_dir}"
        )
        source = events_jsonl

    events = []
    for line in source.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue

    # Group by turn
    turns: list[TurnEvents] = []
    current_turn: TurnEvents | None = None
    last_snapshot: dict | None = None
    winner: str | None = None

    for event in events:
        event_type = event.get("type", "")

        if event_type == "game_action":
            message = _strip_html(event.get("message", ""))
            # Detect turn boundaries
            turn_match = TURN_RE.match(message)
            if turn_match:
                # Finalize previous turn with last snapshot
                if current_turn is not None and last_snapshot is not None:
                    current_turn.board_summary = _compact_board(last_snapshot)
                turn_num = int(turn_match.group(1))
                active = turn_match.group(2)
                current_turn = TurnEvents(turn_number=turn_num, active_player=active)
                turns.append(current_turn)
                last_snapshot = None
                continue

            # Filter noise
            if _is_noise(message):
                continue

            # Check for winner
            won_match = re.match(r"^(.+?) has won the game$", message)
            if won_match:
                winner = won_match.group(1)

            if current_turn is not None:
                current_turn.actions.append(message)
            # Actions before the first turn (mulligans, etc.) get their own turn 0
            elif turns or message:
                if not turns:
                    current_turn = TurnEvents(turn_number=0, active_player="Pregame")
                    turns.append(current_turn)
                if current_turn is not None:
                    current_turn.actions.append(message)

        elif event_type == "player_chat":
            player = event.get("from", "?")
            message = html.unescape(event.get("message", ""))
            if current_turn is not None:
                current_turn.chat.append((player, message))
            elif turns:
                turns[-1].chat.append((player, message))

        elif event_type == "state_snapshot":
            last_snapshot = event

        elif event_type == "llm_response":
            reasoning = event.get("reasoning", "")
            player = event.get("player", "")
            if reasoning and player and current_turn is not None:
                # Keep the longest reasoning per player per turn
                existing = current_turn.reasoning.get(player, "")
                if len(reasoning) > len(existing):
                    current_turn.reasoning[player] = reasoning

        elif event_type == "game_over":
            msg = _strip_html(event.get("message", ""))
            m = re.match(r"Player (.+?) is the winner", msg)
            if m:
                winner = m.group(1)

    # Finalize last turn
    if current_turn is not None and last_snapshot is not None:
        current_turn.board_summary = _compact_board(last_snapshot)

    # Fall back to meta winner
    if winner is None:
        # Check meta for youtube or other hints -- no, just leave it None

        pass

    return GameNarrative(
        game_id=game_dir.name,
        game_type=meta.get("game_type", ""),
        deck_type=meta.get("deck_type", ""),
        players=players,
        winner=winner,
        turns=turns,
    )


def format_narrative(narrative: GameNarrative) -> str:
    """Render the game narrative as structured text for the LLM prompt."""
    lines = []
    lines.append(f"GAME: {narrative.game_type} - {narrative.deck_type}")
    lines.append(f"Game ID: {narrative.game_id}")
    lines.append("Players:")
    for p in narrative.players:
        model_str = f" ({p['model']})" if p.get("model") else ""
        deck_str = f" playing {p['deck_name']}" if p.get("deck_name") else ""
        lines.append(f"  {p['name']}{model_str}{deck_str}")
    if narrative.winner:
        lines.append(f"Winner: {narrative.winner}")
    else:
        lines.append("Winner: (unknown)")
    lines.append("")

    for turn in narrative.turns:
        if turn.turn_number == 0:
            lines.append("=== PREGAME ===")
        else:
            lines.append(f"=== TURN {turn.turn_number} ({turn.active_player}) ===")

        if turn.board_summary:
            lines.append(f"Board: {turn.board_summary}")

        if turn.actions:
            lines.append("Actions:")
            for action in turn.actions:
                lines.append(f"  - {action}")

        for player, reasoning in turn.reasoning.items():
            # Truncate very long reasoning
            r = reasoning[:500] + "..." if len(reasoning) > 500 else reasoning
            lines.append(f'{player} was thinking: "{r}"')

        for player, message in turn.chat:
            lines.append(f'  [{player}]: "{message}"')

        lines.append("")

    return "\n".join(lines)


SYSTEM_PROMPT = """\
You are a play-by-play sports commentator for AI Magic: The Gathering games. \
Your job is to provide entertaining, insightful commentary on a completed game \
between AI players (LLMs playing Magic via tool calls).

Write in a broadcast announcer style -- energetic but analytical. Comment on \
strategic decisions (good and bad), pivotal moments, tempo shifts, and the final \
outcome. When you can see what the AI players were thinking (their reasoning), \
reference it for dramatic effect.

You MUST output valid JSON: an array of commentary entries. Each entry has a \
"turn" field (integer) and a "text" field (string). Include:
- turn 0: Introduction / pregame analysis (who are the players, what decks, predictions)
- turn 1..N: Per-turn commentary (2-4 sentences each, unless something dramatic happens)
- turn -1: Conclusion / post-game wrap-up (final thoughts, MVP plays, what decided the game)

Skip turns where nothing interesting happened (routine land drops with no interaction). \
Combine consecutive quiet turns into one entry if needed.

Example output format:
```json
[
  {"turn": 0, "text": "Welcome folks! Today we have..."},
  {"turn": 1, "text": "Turn 1 opens with..."},
  {"turn": 3, "text": "Turns 2-3 were quiet development, but now..."},
  {"turn": -1, "text": "What a game! The key moment was..."}
]
```

Output ONLY the JSON array, no other text."""


def build_messages(narrative: GameNarrative) -> list[dict]:
    """Build the LLM messages for commentary generation."""
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": format_narrative(narrative)},
    ]


def parse_commentary_response(text: str) -> list[dict]:
    """Extract the JSON array from the LLM response text."""
    text = text.strip()
    # Strip markdown code fences if present
    if text.startswith("```"):
        # Remove opening fence (```json or ```)
        first_newline = text.index("\n")
        text = text[first_newline + 1 :]
        # Remove closing fence
        if text.endswith("```"):
            text = text[:-3].strip()

    entries = json.loads(text)
    assert isinstance(entries, list), (
        f"Expected JSON array, got {type(entries).__name__}"
    )
    for entry in entries:
        assert isinstance(entry, dict), (
            f"Expected dict entry, got {type(entry).__name__}"
        )
        assert "turn" in entry, "Missing 'turn' field in commentary entry"
        assert "text" in entry, "Missing 'text' field in commentary entry"
    return entries


async def generate_commentary(
    client: AsyncOpenAI,
    model: str,
    messages: list[dict],
    prices: dict[str, tuple[float, float]],
) -> tuple[str, float]:
    """Call the LLM and return (response_text, cost_usd)."""
    response = await client.chat.completions.create(
        model=model,
        messages=messages,
        max_tokens=MAX_COMMENTARY_TOKENS,
    )
    choice = response.choices[0]
    text = choice.message.content or ""

    # Calculate cost
    cost_usd = 0.0
    usage = response.usage
    if usage:
        price = get_model_price(model, prices)
        if price:
            input_per_m, output_per_m = price
            cost_usd = (
                usage.prompt_tokens * input_per_m / 1_000_000
                + usage.completion_tokens * output_per_m / 1_000_000
            )
        prompt_tokens = usage.prompt_tokens
        completion_tokens = usage.completion_tokens
        print(f"Tokens: {prompt_tokens} input, {completion_tokens} output")

    return text, cost_usd


async def async_main(args: argparse.Namespace) -> None:
    game_dir = LOGS_DIR / args.game_id
    assert game_dir.is_dir(), f"{game_dir} is not a directory"

    print(f"Loading game narrative from {game_dir.name}...")
    narrative = load_game_narrative(game_dir)
    print(f"  {len(narrative.turns)} turns, {len(narrative.players)} players")
    if narrative.winner:
        print(f"  Winner: {narrative.winner}")

    # Set up LLM client
    model = args.model
    base_url = args.base_url
    api_key_env = required_api_key_env(base_url)
    api_key = os.environ.get(api_key_env)
    assert api_key, f"Missing {api_key_env} environment variable"

    client = AsyncOpenAI(api_key=api_key, base_url=base_url)
    prices = load_prices()

    messages = build_messages(narrative)
    prompt_text = messages[1]["content"]
    print(f"  Narrative: {len(prompt_text)} chars")

    print(f"Generating commentary with {model}...")
    raw_text, cost_usd = await generate_commentary(client, model, messages, prices)

    entries = parse_commentary_response(raw_text)
    print(f"  {len(entries)} commentary entries")

    # Write output
    output = {
        "model": model,
        "cost_usd": round(cost_usd, 6),
        "entries": entries,
    }
    output_path = game_dir / "commentary.json"
    output_path.write_text(json.dumps(output, indent=2) + "\n")
    print(f"Commentary written to {output_path}")
    print(f"Cost: ${cost_usd:.4f}")


def main():
    parser = argparse.ArgumentParser(
        description="Generate AI commentary for a completed game"
    )
    parser.add_argument("game_id", help=f"Game directory name under {LOGS_DIR}")
    parser.add_argument(
        "--model", default=DEFAULT_MODEL, help=f"LLM model (default: {DEFAULT_MODEL})"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="API base URL")
    args = parser.parse_args()
    asyncio.run(async_main(args))


if __name__ == "__main__":
    main()
