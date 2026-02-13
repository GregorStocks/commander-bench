#!/usr/bin/env python3
"""Generate AI play-by-play commentary for a completed game.

Reads from the exported .json.gz in the website games directory, generates
commentary via an LLM, and writes the commentary back into the gz.
"""

import argparse
import asyncio
import gzip
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

WEBSITE_GAMES_DIR = (
    Path(__file__).resolve().parent.parent / "website" / "public" / "games"
)
DEFAULT_MODEL = "google/gemini-2.5-flash"
MAX_COMMENTARY_TOKENS = 4096

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


def load_game_narrative(game: dict) -> GameNarrative:
    """Build a structured narrative from an exported game JSON dict."""
    game_id = game.get("id", "")

    # Build player info
    players = []
    for p in game.get("players", []):
        players.append(
            {
                "name": p.get("name", "?"),
                "model": p.get("model", ""),
                "deck_name": p.get("deckName") or p.get("commander"),
                "type": p.get("type", "?"),
            }
        )

    # Build a map of (turn, player) -> reasoning from llmEvents
    # We want the longest reasoning per player per turn. To associate
    # llm_response events with turns, we use the snapshots' seq numbers
    # to determine which turn each event falls into.
    snapshot_turn_boundaries: list[tuple[int, int]] = []  # (seq, turn)
    for snap in game.get("snapshots", []):
        snapshot_turn_boundaries.append((snap.get("seq", 0), snap.get("turn", 0)))
    snapshot_turn_boundaries.sort()

    def _seq_to_turn(ts: str) -> int | None:
        """Map a timestamp to a turn number using snapshot boundaries."""
        # We don't have seq on llmEvents, so match by timestamp
        # against snapshots. Just use the nearest snapshot turn.
        # This is approximate but good enough for commentary.
        return None

    # Build reasoning index: we'll assign reasoning to turns by
    # matching timestamps. LLM events between two snapshots belong
    # to the turn of the earlier snapshot.
    llm_reasoning: dict[str, list[tuple[str, str]]] = {}  # ts -> [(player, reasoning)]
    for event in game.get("llmEvents", []):
        if event.get("type") == "llm_response":
            reasoning = event.get("reasoning", "")
            player = event.get("player", "")
            if reasoning and player:
                ts = event.get("ts", "")
                llm_reasoning.setdefault(ts, []).append((player, reasoning))

    # Group actions by turn
    actions = game.get("actions", [])
    snapshots = game.get("snapshots", [])

    # Build snapshot lookup by seq for board summaries
    snapshot_by_seq: dict[int, dict] = {}
    for snap in snapshots:
        snapshot_by_seq[snap.get("seq", 0)] = snap

    # Build a sorted list of snapshot seqs per turn for assigning reasoning
    turn_snapshot_seqs: dict[int, list[int]] = {}
    for snap in snapshots:
        turn = snap.get("turn", 0)
        turn_snapshot_seqs.setdefault(turn, []).append(snap.get("seq", 0))

    turns: list[TurnEvents] = []
    current_turn: TurnEvents | None = None
    winner = game.get("winner")

    for action in actions:
        message = action.get("message", "")
        action_type = action.get("type")

        if action_type == "chat":
            player = action.get("from", "?")
            chat_msg = html.unescape(message)
            if current_turn is not None:
                current_turn.chat.append((player, chat_msg))
            continue

        # Regular game action
        turn_match = TURN_RE.match(message)
        if turn_match:
            turn_num = int(turn_match.group(1))
            active = turn_match.group(2)
            current_turn = TurnEvents(turn_number=turn_num, active_player=active)
            turns.append(current_turn)
            continue

        if _is_noise(message):
            continue

        if current_turn is not None:
            current_turn.actions.append(message)
        elif message:
            if not turns:
                current_turn = TurnEvents(turn_number=0, active_player="Pregame")
                turns.append(current_turn)
            if current_turn is not None:
                current_turn.actions.append(message)

    # Assign board summaries from snapshots: for each turn, use the last
    # snapshot with that turn number
    last_snapshot_per_turn: dict[int, dict] = {}
    for snap in snapshots:
        turn = snap.get("turn", 0)
        last_snapshot_per_turn[turn] = snap
    for turn in turns:
        snap = last_snapshot_per_turn.get(turn.turn_number)
        if snap:
            turn.board_summary = _compact_board(snap)

    # Assign reasoning from llmEvents to turns by timestamp
    # Sort llm events and snapshots by timestamp, then assign each llm event
    # to the current turn based on which TURN action preceded it
    all_timestamps: list[tuple[str, str, str, str]] = []  # (ts, kind, player, data)
    for event in game.get("llmEvents", []):
        if event.get("type") == "llm_response" and event.get("reasoning"):
            all_timestamps.append(
                (
                    event.get("ts", ""),
                    "reasoning",
                    event.get("player", ""),
                    event.get("reasoning", ""),
                )
            )
    # Also get action timestamps to know turn boundaries
    for action in actions:
        msg = action.get("message", "")
        if TURN_RE.match(msg):
            turn_match = TURN_RE.match(msg)
            assert turn_match is not None
            all_timestamps.append(
                (
                    action.get("ts", ""),
                    "turn",
                    "",
                    turn_match.group(1),
                )
            )
    all_timestamps.sort(key=lambda x: x[0])

    turn_map: dict[int, TurnEvents] = {t.turn_number: t for t in turns}
    current_turn_num = 0
    for ts, kind, player, data in all_timestamps:
        if kind == "turn":
            current_turn_num = int(data)
        elif kind == "reasoning":
            t = turn_map.get(current_turn_num)
            if t and player:
                existing = t.reasoning.get(player, "")
                if len(data) > len(existing):
                    t.reasoning[player] = data

    return GameNarrative(
        game_id=game_id,
        game_type=game.get("gameType", "") or "",
        deck_type=game.get("deckType", "") or "",
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


def _load_game_gz(game_id: str, games_dir: Path) -> tuple[dict, Path]:
    """Load a game from its .json.gz file. Returns (game_dict, gz_path)."""
    gz_path = games_dir / f"{game_id}.json.gz"
    assert gz_path.exists(), f"No exported game found: {gz_path}"
    game = json.loads(gzip.decompress(gz_path.read_bytes()))
    return game, gz_path


async def async_main(args: argparse.Namespace) -> None:
    games_dir = Path(args.games_dir)
    game_id = args.game_id

    print(f"Loading game from {games_dir / (game_id + '.json.gz')}...")
    game, gz_path = _load_game_gz(game_id, games_dir)
    narrative = load_game_narrative(game)
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

    # Write commentary back into the gz
    game["commentary"] = entries
    gz_path.write_bytes(gzip.compress(json.dumps(game).encode()))
    print(f"Commentary written to {gz_path}")
    print(f"Cost: ${cost_usd:.4f}")


def main():
    parser = argparse.ArgumentParser(
        description="Generate AI commentary for a completed game"
    )
    parser.add_argument("game_id", help="Game ID (name of the .json.gz file)")
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL,
        help=f"LLM model (default: {DEFAULT_MODEL})",
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="API base URL")
    parser.add_argument(
        "--games-dir",
        default=str(WEBSITE_GAMES_DIR),
        help=f"Games directory (default: {WEBSITE_GAMES_DIR})",
    )
    args = parser.parse_args()
    asyncio.run(async_main(args))


if __name__ == "__main__":
    main()
