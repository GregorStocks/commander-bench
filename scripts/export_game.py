#!/usr/bin/env python3
"""Export a game log directory into a single JSON file for the website visualizer."""

import json
import re
import sys
from pathlib import Path

WEBSITE_GAMES_DIR = (
    Path(__file__).resolve().parent.parent / "website" / "public" / "games"
)
LOGS_DIR = Path.home() / "mage-bench-logs"

FONT_TAG_RE = re.compile(r"<font[^>]*>|</font>")
OBJECT_ID_RE = re.compile(r"\s*\[[0-9a-f]{3,}\]")
DECKLIST_RE = re.compile(r"(?:SB:\s*)?(\d+)\s+\[([^:]+):([^\]]+)\]\s+(.+)")

# LLM event types to include in the website export
_LLM_EVENT_TYPES = {
    "llm_response",
    "tool_call",
    "stall",
    "context_trim",
    "context_reset",
    "llm_error",
    "auto_pilot_mode",
}


def _strip_html(message: str) -> str:
    """Remove <font> tags and [hex_id] suffixes from action messages."""
    message = FONT_TAG_RE.sub("", message)
    message = OBJECT_ID_RE.sub("", message)
    return message.strip()


def _build_card_images(players_meta: list[dict]) -> dict[str, str]:
    """Build card name -> Scryfall small image URL map from decklists."""
    images = {}
    for player in players_meta:
        for entry in player.get("decklist", []):
            m = DECKLIST_RE.match(entry)
            if m:
                set_code = m.group(2).lower()
                card_num = m.group(3)
                card_name = m.group(4).strip()
                images[card_name] = (
                    f"https://api.scryfall.com/cards/{set_code}/{card_num}"
                    f"?format=image&version=small"
                )
    return images


def _extract_commander(player_meta: dict) -> str | None:
    """Find commander name from decklist (SB: entries)."""
    for entry in player_meta.get("decklist", []):
        if entry.startswith("SB:"):
            m = DECKLIST_RE.match(entry)
            if m:
                return m.group(4).strip()
    return None


def _read_llm_events(game_dir: Path) -> tuple[list[dict], dict[str, float]]:
    """Read LLM events from all *_llm.jsonl files.

    Returns (llm_events sorted by timestamp, {player_name: total_cost_usd}).
    """
    events = []
    player_costs: dict[str, float] = {}

    for path in sorted(game_dir.glob("*_llm.jsonl")):
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError:
                continue

            event_type = raw.get("type", "")
            player = raw.get("player", "")

            # Track per-player cost from game_end or cumulative_cost_usd
            if event_type == "game_end" and "total_cost_usd" in raw:
                player_costs[player] = raw["total_cost_usd"]
            elif "cumulative_cost_usd" in raw:
                player_costs[player] = raw["cumulative_cost_usd"]

            if event_type not in _LLM_EVENT_TYPES:
                continue

            # Build the exported event with camelCase keys
            exported: dict = {
                "ts": raw.get("ts", ""),
                "player": player,
                "type": event_type,
            }

            if event_type == "llm_response":
                exported["reasoning"] = raw.get("reasoning", "")
                if raw.get("thinking"):
                    exported["thinking"] = raw["thinking"]
                if raw.get("tool_calls"):
                    exported["toolCalls"] = raw["tool_calls"]
                usage = raw.get("usage")
                if usage:
                    exported["usage"] = {
                        "promptTokens": usage.get("prompt_tokens", 0),
                        "completionTokens": usage.get("completion_tokens", 0),
                    }
                if "cost_usd" in raw:
                    exported["costUsd"] = raw["cost_usd"]
            elif event_type == "tool_call":
                exported["tool"] = raw.get("tool", "")
                exported["args"] = raw.get("arguments", {})
                exported["result"] = raw.get("result", "")
                if "latency_ms" in raw:
                    exported["latencyMs"] = raw["latency_ms"]
            elif event_type == "stall":
                exported["turnsWithoutProgress"] = raw.get("turns_without_progress", 0)
                exported["lastTools"] = raw.get("last_tools", [])
            elif event_type == "context_trim":
                exported["messagesBefore"] = raw.get("messages_before", 0)
                exported["messagesAfter"] = raw.get("messages_after", 0)
            elif event_type == "context_reset":
                exported["reason"] = raw.get("reason", "")
            elif event_type == "llm_error":
                exported["errorType"] = raw.get("error_type", "")
                exported["errorMessage"] = raw.get("error_message", "")
            elif event_type == "auto_pilot_mode":
                exported["reason"] = raw.get("reason", "")

            events.append(exported)

    # Sort by timestamp
    events.sort(key=lambda e: e.get("ts", ""))

    return events, player_costs


def export_game(game_dir: Path, website_games_dir: Path) -> Path:
    """Export a game directory to a website JSON file. Returns the output path."""
    events_path = game_dir / "game_events.jsonl"
    meta_path = game_dir / "game_meta.json"

    if not events_path.exists():
        raise FileNotFoundError(f"No game_events.jsonl in {game_dir}")

    # Load metadata
    meta = {}
    if meta_path.exists():
        meta = json.loads(meta_path.read_text())

    # Parse events
    snapshots = []
    actions = []
    game_over = None

    for line in events_path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        event = json.loads(line)
        event_type = event.get("type")

        if event_type == "state_snapshot":
            # Keep all fields except type (preserve ts + seq for timeline matching)
            snap = {k: v for k, v in event.items() if k != "type"}
            snapshots.append(snap)
        elif event_type == "game_action":
            actions.append(
                {
                    "ts": event.get("ts", ""),
                    "seq": event.get("seq", 0),
                    "message": _strip_html(event.get("message", "")),
                }
            )
        elif event_type == "game_over":
            game_over = {
                "seq": event.get("seq", 0),
                "message": _strip_html(event.get("message", "")),
            }

    # Read LLM logs
    llm_events, player_costs = _read_llm_events(game_dir)

    # Build card images map from decklists
    card_images = _build_card_images(meta.get("players", []))

    # Extract game metadata
    game_id = game_dir.name
    total_turns = max((s.get("turn", 0) for s in snapshots), default=0)

    winner = None
    if game_over:
        # "Player X is the winner"
        msg = game_over["message"]
        m = re.match(r"Player (.+?) is the winner", msg)
        if m:
            winner = m.group(1)

    players_summary = []
    for p in meta.get("players", []):
        name = p.get("name", "?")
        entry: dict = {
            "name": name,
            "type": p.get("type", "?"),
            "commander": _extract_commander(p),
        }
        if p.get("model"):
            entry["model"] = p["model"]
        if name in player_costs:
            entry["totalCostUsd"] = round(player_costs[name], 4)
        players_summary.append(entry)

    # Build output
    output = {
        "id": game_id,
        "timestamp": meta.get("timestamp", ""),
        "totalTurns": total_turns,
        "winner": winner,
        "players": players_summary,
        "cardImages": card_images,
        "snapshots": snapshots,
        "actions": actions,
        "llmEvents": llm_events,
        "gameOver": game_over,
    }

    website_games_dir.mkdir(parents=True, exist_ok=True)
    output_path = website_games_dir / f"{game_id}.json"
    output_path.write_text(json.dumps(output, indent=2))

    # Update index.json
    _update_index(website_games_dir, game_id, output)

    return output_path


def _update_index(games_dir: Path, game_id: str, game_data: dict) -> None:
    """Add or update a game entry in index.json."""
    index_path = games_dir / "index.json"
    games = []
    if index_path.exists():
        games = json.loads(index_path.read_text())

    # Remove existing entry for this game if any
    games = [g for g in games if g.get("id") != game_id]

    # Add new entry (summary only, no full data)
    games.append(
        {
            "id": game_id,
            "timestamp": game_data.get("timestamp", ""),
            "totalTurns": game_data.get("totalTurns", 0),
            "winner": game_data.get("winner"),
            "players": game_data.get("players", []),
        }
    )

    # Sort by id descending (newest first)
    games.sort(key=lambda g: g.get("id", ""), reverse=True)

    index_path.write_text(json.dumps(games, indent=2))


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <game_id> [website_games_dir]")
        print(f"  game_id: directory name under {LOGS_DIR}")
        sys.exit(1)

    game_id = sys.argv[1]
    game_dir = LOGS_DIR / game_id
    if not game_dir.is_dir():
        print(f"Error: {game_dir} is not a directory")
        sys.exit(1)

    games_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else WEBSITE_GAMES_DIR
    output_path = export_game(game_dir, games_dir)
    size_kb = output_path.stat().st_size // 1024
    print(f"Exported {game_id} -> {output_path} ({size_kb} KB)")


if __name__ == "__main__":
    main()
