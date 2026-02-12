"""Structured game logging: JSONL writer, decklist parser, post-game merge."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from types import TracebackType
from zoneinfo import ZoneInfo


class GameLogWriter:
    """Appends JSONL events to {player}_llm.jsonl."""

    _TZ = ZoneInfo("America/Los_Angeles")

    def __init__(self, game_dir: Path, player_name: str, suffix: str = "llm"):
        self._path = game_dir / f"{player_name}_{suffix}.jsonl"
        self._player = player_name
        self._seq = 0
        self._last_cumulative_cost_usd = 0.0
        self._file = open(self._path, "a")

    def emit(self, event_type: str, **fields):
        self._seq += 1
        ts = datetime.now(self._TZ).isoformat(timespec="milliseconds")
        if "cumulative_cost_usd" in fields:
            try:
                self._last_cumulative_cost_usd = float(fields["cumulative_cost_usd"])
            except (TypeError, ValueError):
                pass
        event = {
            "ts": ts,
            "seq": self._seq,
            "type": event_type,
            "player": self._player,
            **fields,
        }
        self._file.write(json.dumps(event, separators=(",", ":")) + "\n")
        self._file.flush()

    def last_cumulative_cost_usd(self) -> float:
        return self._last_cumulative_cost_usd

    def close(self):
        self._file.close()

    def __enter__(self) -> GameLogWriter:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_val: BaseException | None,
        exc_tb: TracebackType | None,
    ) -> None:
        self.close()


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


def _parse_ts(value: str) -> float | None:
    if not value:
        return None
    try:
        if value.endswith("Z"):
            value = value[:-1] + "+00:00"
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except Exception:
        return None


def merge_game_log(game_dir: Path) -> None:
    """Merge game_events.jsonl + all *_llm.jsonl into game.jsonl, sorted by timestamp."""
    all_events = []
    order = 0

    # Collect from all source files
    for pattern in ["game_events.jsonl", "*_llm.jsonl"]:
        for path in game_dir.glob(pattern):
            for _line_num, line in enumerate(path.read_text().splitlines(), 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                    ts_value = _parse_ts(event.get("ts", ""))
                    all_events.append((ts_value, order, line))
                    order += 1
                except json.JSONDecodeError:
                    pass

    # Sort by timestamp (stable sort preserves file order for same-ts events)
    all_events.sort(key=lambda x: (x[0] is None, x[0] or 0.0, x[1]))

    # Write merged file
    merged_path = game_dir / "game.jsonl"
    with open(merged_path, "w") as f:
        for _, _, line in all_events:
            f.write(line + "\n")
