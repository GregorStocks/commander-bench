"""Structured game logging: JSONL writer, decklist parser, post-game merge."""

import json
import time
from datetime import datetime, timezone
from pathlib import Path


class GameLogWriter:
    """Appends JSONL events to {player}_llm.jsonl."""

    def __init__(self, game_dir: Path, player_name: str):
        self._path = game_dir / f"{player_name}_llm.jsonl"
        self._player = player_name
        self._seq = 0
        self._file = open(self._path, "a")

    def emit(self, event_type: str, **fields):
        self._seq += 1
        event = {
            "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "seq": self._seq,
            "type": event_type,
            "player": self._player,
            **fields,
        }
        self._file.write(json.dumps(event, separators=(",", ":")) + "\n")
        self._file.flush()

    def close(self):
        self._file.close()


def read_decklist(deck_path: Path) -> list[str]:
    """Parse a .dck file into a list of card entries (e.g. '1 Sol Ring')."""
    if not deck_path.exists():
        return []
    entries = []
    for line in deck_path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and not line.startswith("//"):
            # .dck format: "1 [SET:123] Card Name" or "SB: 1 [SET:123] Card Name"
            entries.append(line)
    return entries


def merge_game_log(game_dir: Path) -> None:
    """Merge game_events.jsonl + all *_llm.jsonl into game.jsonl, sorted by timestamp."""
    all_events = []

    # Collect from all source files
    for pattern in ["game_events.jsonl", "*_llm.jsonl"]:
        for path in game_dir.glob(pattern):
            for line_num, line in enumerate(path.read_text().splitlines(), 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                    # Use ts for sorting; fall back to file order
                    all_events.append((event.get("ts", ""), line_num, str(path), line))
                except json.JSONDecodeError:
                    pass

    # Sort by timestamp (stable sort preserves file order for same-ts events)
    all_events.sort(key=lambda x: x[0])

    # Write merged file
    merged_path = game_dir / "game.jsonl"
    with open(merged_path, "w") as f:
        for _, _, _, line in all_events:
            f.write(line + "\n")
