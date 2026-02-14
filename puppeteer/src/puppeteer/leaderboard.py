"""Generate leaderboard data from game results using OpenSkill ratings."""

from __future__ import annotations

import gzip
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from openskill.models import PlackettLuce

_LOST_GAME_RE = re.compile(r"^(.+?) has lost the game\.$")

# Scale raw OpenSkill ordinals (centered at 0) to a DCI-style rating.
# DCI Magic used Elo starting at 1600.
_RATING_BASE = 1600
_RATING_SCALE = 100
_MIN_GAMES = 3  # Minimum games to appear on leaderboard

# Map XMage deckType strings to canonical format names for leaderboard bucketing.
_DECK_TYPE_TO_FORMAT: dict[str, str] = {
    "Constructed - Standard": "standard",
    "Constructed - Modern": "modern",
    "Constructed - Legacy": "legacy",
    "Variant Magic - Freeform Commander": "commander",
    "Variant Magic - Commander": "commander",
}

# Display labels for format tabs.
FORMAT_LABELS: dict[str, str] = {
    "combined": "Combined",
    "standard": "Standard",
    "modern": "Modern",
    "legacy": "Legacy",
    "commander": "Commander",
}


def derive_format(game: dict) -> str:
    """Derive canonical format name from game data.

    Uses deckType if present, falls back to 'commander' for
    backward compatibility with existing games.
    """
    deck_type = game.get("deckType", "")
    if deck_type in _DECK_TYPE_TO_FORMAT:
        return _DECK_TYPE_TO_FORMAT[deck_type]
    # Backward compat: old games without deckType were all Commander
    game_type = game.get("gameType", "")
    if "Commander" in game_type or not deck_type:
        return "commander"
    return deck_type.lower().replace(" ", "-")


def _display_rating(ordinal: float) -> int:
    """Convert raw OpenSkill ordinal to display rating."""
    return round(ordinal * _RATING_SCALE + _RATING_BASE)


_PROVIDER_DISPLAY: dict[str, str] = {
    "anthropic": "Anthropic",
    "google": "Google",
    "openai": "OpenAI",
    "mistralai": "Mistral AI",
    "deepseek": "DeepSeek",
    "meta-llama": "Meta",
}


def capitalize_provider(slug: str) -> str:
    """Convert provider slug to display name."""
    return _PROVIDER_DISPLAY.get(slug, slug.title())


def derive_display_name(model_id: str) -> str:
    """Derive a display name from a model ID not in the registry.

    Takes the part after '/' and title-cases it with spaces.
    E.g. "mistralai/devstral-small" -> "Devstral Small"
    """
    slug = model_id.split("/", 1)[-1]
    return slug.replace("-", " ").title()


def _player_key(player: dict) -> str:
    """Build aggregation key: 'model_id::effort' or just 'model_id'."""
    model_id = player.get("model", "")
    effort = player.get("reasoningEffort") or player.get("reasoning_effort")
    if effort:
        return f"{model_id}::{effort}"
    return model_id


def _split_key(key: str) -> tuple[str, str | None]:
    """Split aggregation key into (model_id, reasoning_effort)."""
    if "::" in key:
        model_id, effort = key.split("::", 1)
        return model_id, effort
    return key, None


def load_model_registry(models_json: Path) -> dict[str, str]:
    """Load model ID -> display name mapping from models.json."""
    if not models_json.exists():
        return {}
    data = json.loads(models_json.read_text())
    return {m["id"]: m["name"] for m in data.get("models", [])}


def extract_placements(game: dict, games_dir: Path | None = None) -> dict[str, int]:
    """Extract player placements from game data.

    Uses the 'placement' field if present on players. Otherwise, falls back
    to parsing "X has lost the game." messages from the full game JSON file
    (if games_dir is provided).

    Returns {player_name: placement} where 1=winner, 2=2nd, etc.
    """
    players = game.get("players", [])

    # Check if placements are already in the index data
    if any("placement" in p for p in players):
        return {p["name"]: p["placement"] for p in players if "placement" in p}

    # Fall back to reading actions from the full game JSON
    if games_dir is None:
        return _placements_from_winner(game)

    game_path = games_dir / f"{game['id']}.json.gz"
    if not game_path.exists():
        return _placements_from_winner(game)

    full_game = json.loads(gzip.decompress(game_path.read_bytes()))
    actions = full_game.get("actions", [])
    player_names = [p.get("name", "?") for p in players]
    winner = game.get("winner")

    eliminations = []
    for a in actions:
        m = _LOST_GAME_RE.match(a.get("message", ""))
        if m:
            eliminations.append(m.group(1))

    placements: dict[str, int] = {}
    if winner:
        placements[winner] = 1
        for i, name in enumerate(reversed(eliminations)):
            placements[name] = i + 2
    elif eliminations:
        surviving = [n for n in player_names if n not in eliminations]
        for name in surviving:
            placements[name] = 1
        for i, name in enumerate(reversed(eliminations)):
            placements[name] = len(surviving) + i + 1

    return placements


def _placements_from_winner(game: dict) -> dict[str, int]:
    """Minimal placement extraction using only winner field."""
    winner = game.get("winner")
    if not winner:
        return {}
    placements: dict[str, int] = {}
    for p in game.get("players", []):
        name = p.get("name", "?")
        placements[name] = 1 if name == winner else 2
    return placements


def compute_ratings(
    games_index: list[dict],
    games_dir: Path | None = None,
) -> tuple[dict[str, float], list[dict]]:
    """Compute OpenSkill ratings from game history.

    Uses PlackettLuce model with full placement orderings when available.
    Games with no placement data are skipped (no rating update) but still
    record snapshots.

    Returns (final_ratings, per_game_ratings) where per_game_ratings is a list
    of dicts with {id, players: [{model, ratingBefore, ratingAfter}]}.
    """
    model = PlackettLuce()
    # model_id -> OpenSkill rating object
    ratings: dict[str, Any] = {}
    per_game: list[dict] = []

    sorted_games = sorted(games_index, key=lambda g: g.get("timestamp", ""))

    for game in sorted_games:
        pilots = [p for p in game.get("players", []) if p.get("type") == "pilot" and p.get("model")]
        if len(pilots) < 2:
            # Need at least 2 pilots for rating; record snapshot with no change
            for p in pilots:
                key = _player_key(p)
                if key not in ratings:
                    ratings[key] = model.rating(name=key)
            if pilots:
                key = _player_key(pilots[0])
                display = _display_rating(ratings[key].ordinal())
                per_game.append(
                    {
                        "id": game.get("id", ""),
                        "players": [{"key": key, "ratingBefore": display, "ratingAfter": display}],
                    }
                )
            continue

        # Ensure all pilots have a rating
        for p in pilots:
            key = _player_key(p)
            if key not in ratings:
                ratings[key] = model.rating(name=key)

        # Record before ratings
        pilot_keys = [_player_key(p) for p in pilots]
        before = {key: _display_rating(ratings[key].ordinal()) for key in pilot_keys}

        # Get placements
        placements = extract_placements(game, games_dir)

        # Build teams (each player is a 1-person team) and ranks
        teams = [[ratings[key]] for key in pilot_keys]

        has_placements = any(p["name"] in placements for p in pilots)
        if has_placements:
            # Convert placements to ranks for OpenSkill (lower = better)
            ranks: list[float] = []
            for p in pilots:
                placement = placements.get(p["name"])
                # Default to last place if no placement
                ranks.append(float(placement if placement is not None else len(pilots)))
            updated = model.rate(teams, ranks=ranks)
        else:
            # No placement data — skip rating update
            updated = teams

        # Update ratings
        for i, key in enumerate(pilot_keys):
            ratings[key] = updated[i][0]

        # Record after ratings
        after = {key: _display_rating(ratings[key].ordinal()) for key in pilot_keys}
        per_game.append(
            {
                "id": game.get("id", ""),
                "players": [
                    {
                        "key": key,
                        "ratingBefore": before[key],
                        "ratingAfter": after[key],
                    }
                    for key in pilot_keys
                ],
            }
        )

    # Build final display ratings
    final: dict[str, float] = {}
    for mid, r in ratings.items():
        final[mid] = _display_rating(r.ordinal())

    return final, per_game


def generate_leaderboard(
    games_index: list[dict],
    model_registry: dict[str, str],
    games_dir: Path | None = None,
    min_games: int = _MIN_GAMES,
) -> tuple[dict, dict[str, dict[str, dict[str, int]]]]:
    """Aggregate game results into leaderboard data.

    Returns (benchmark_results, ratings_by_game) where ratings_by_game is
    {game_id: {model_id: {before, after}}}.
    """
    # Filter to games with a winner for leaderboard purposes
    scored_games = [g for g in games_index if g.get("winner")]

    final_ratings, per_game = compute_ratings(scored_games, games_dir)

    # Build ratings_by_game lookup
    ratings_by_game: dict[str, dict[str, dict[str, int]]] = {}
    for game_entry in per_game:
        game_id = game_entry["id"]
        ratings_by_game[game_id] = {
            p["key"]: {"before": p["ratingBefore"], "after": p["ratingAfter"]} for p in game_entry["players"]
        }

    # Aggregate per-player-key stats (model_id::effort or just model_id)
    stats: dict[str, dict[str, float]] = {}
    for game in scored_games:
        for p in game.get("players", []):
            if p.get("type") != "pilot" or not p.get("model"):
                continue
            key = _player_key(p)
            if key not in stats:
                stats[key] = {
                    "games_played": 0,
                    "wins": 0,
                    "total_cost": 0.0,
                    "total_tool_calls_ok": 0,
                    "total_tool_calls_failed": 0,
                }
            stats[key]["games_played"] += 1
            if game.get("winner") == p["name"]:
                stats[key]["wins"] += 1
            stats[key]["total_cost"] += p.get("totalCostUsd", 0.0)
            stats[key]["total_tool_calls_ok"] += p.get("toolCallsOk", 0)
            stats[key]["total_tool_calls_failed"] += p.get("toolCallsFailed", 0)

    # Build models list
    models: list[dict[str, str | int | float]] = []
    for key, s in stats.items():
        model_id, effort = _split_key(key)
        games_played = int(s["games_played"])
        if games_played < min_games:
            continue
        wins = int(s["wins"])
        win_rate = wins / games_played
        avg_cost = s["total_cost"] / games_played
        provider_slug = model_id.split("/", 1)[0]
        rating = final_ratings.get(key, _RATING_BASE)

        display_name = model_registry.get(model_id) or derive_display_name(model_id)
        if effort:
            display_name = f"{display_name} ({effort})"

        avg_tool_calls_ok = s["total_tool_calls_ok"] / games_played
        avg_tool_calls_failed = s["total_tool_calls_failed"] / games_played
        entry: dict[str, str | int | float] = {
            "modelId": model_id,
            "modelName": display_name,
            "provider": capitalize_provider(provider_slug),
            "rating": rating,
            "gamesPlayed": games_played,
            "winRate": round(win_rate, 4),
            "avgApiCost": round(avg_cost, 2),
            "avgToolCallsOk": round(avg_tool_calls_ok, 1),
            "avgToolCallsFailed": round(avg_tool_calls_failed, 1),
        }
        if effort:
            entry["reasoningEffort"] = effort
        models.append(entry)

    # Sort by rating desc, then games_played desc
    models.sort(key=lambda m: (-m["rating"], -m["gamesPlayed"]))  # type: ignore[operator]

    benchmark_results = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "totalGames": len(scored_games),
        "models": models,
    }

    return benchmark_results, ratings_by_game


def generate_all_leaderboards(
    games_index: list[dict],
    model_registry: dict[str, str],
    games_dir: Path | None = None,
    min_games: int = _MIN_GAMES,
) -> tuple[dict[str, dict], dict[str, dict[str, dict[str, int]]]]:
    """Generate per-format and combined leaderboards.

    Returns (format_results, ratings_by_game) where format_results maps
    format name -> benchmark_results dict. The "combined" key has all
    formats merged into one unified rating pool.
    """
    # Combined leaderboard excludes commander (different format, skews ratings)
    non_commander = [g for g in games_index if derive_format(g) != "commander"]
    combined_results, ratings_by_game = generate_leaderboard(
        non_commander, model_registry, games_dir, min_games=min_games
    )

    format_results: dict[str, dict] = {"combined": combined_results}

    # Partition games by format
    by_format: dict[str, list[dict]] = {}
    for game in games_index:
        fmt = derive_format(game)
        by_format.setdefault(fmt, []).append(game)

    # Generate per-format leaderboards
    for fmt, fmt_games in by_format.items():
        fmt_results, _ = generate_leaderboard(fmt_games, model_registry, games_dir, min_games=min_games)
        format_results[fmt] = fmt_results

    return format_results, ratings_by_game


def generate_leaderboard_file(games_dir: Path, data_dir: Path, models_json: Path, min_games: int = _MIN_GAMES) -> Path:
    """Generate leaderboard files from game data.

    Writes:
    - data_dir/benchmark-results.json (model summaries for leaderboard page)
    - games_dir/../data/elo.json (per-game ratings for game pages)

    Returns the benchmark-results.json path.
    """
    games_index = []
    for gz_path in sorted(games_dir.glob("game_*.json.gz")):
        game = json.loads(gzip.decompress(gz_path.read_bytes()))
        players = game.get("players", [])

        # Compute tool call counts from llmEvents if not already on players
        if players and not any("toolCallsOk" in p for p in players):
            tool_ok: dict[str, int] = {}
            tool_failed: dict[str, int] = {}
            for ev in game.get("llmEvents", []):
                if ev.get("type") != "tool_call":
                    continue
                player = ev.get("player", "")
                if not player:
                    continue
                result_str = ev.get("result", "")
                is_failure = False
                if result_str:
                    try:
                        result_obj = json.loads(result_str)
                        if isinstance(result_obj, dict) and result_obj.get("success") is False:
                            is_failure = True
                    except (json.JSONDecodeError, TypeError):
                        pass
                if is_failure:
                    tool_failed[player] = tool_failed.get(player, 0) + 1
                else:
                    tool_ok[player] = tool_ok.get(player, 0) + 1
            for p in players:
                name = p.get("name", "")
                if name in tool_ok or name in tool_failed:
                    p["toolCallsOk"] = tool_ok.get(name, 0)
                    p["toolCallsFailed"] = tool_failed.get(name, 0)

        games_index.append(
            {
                "id": game["id"],
                "timestamp": game.get("timestamp", ""),
                "gameType": game.get("gameType", ""),
                "deckType": game.get("deckType", ""),
                "totalTurns": game.get("totalTurns", 0),
                "winner": game.get("winner"),
                "players": players,
            }
        )

    model_registry = load_model_registry(models_json)
    format_results, ratings_by_game = generate_all_leaderboards(
        games_index, model_registry, games_dir, min_games=min_games
    )

    # Build output with backward-compatible top-level fields from combined
    combined = format_results.get("combined", {"generatedAt": "", "totalGames": 0, "models": []})
    output = {
        "generatedAt": combined.get("generatedAt", ""),
        "totalGames": combined.get("totalGames", 0),
        "models": combined.get("models", []),
        "formats": format_results,
    }

    # Write benchmark-results.json
    data_dir.mkdir(parents=True, exist_ok=True)
    output_path = data_dir / "benchmark-results.json"
    output_path.write_text(json.dumps(output, indent=2) + "\n")

    # Write elo.json to public/data/ (kept as elo.json for backward compat)
    elo_dir = games_dir.parent / "data"
    elo_dir.mkdir(parents=True, exist_ok=True)
    elo_path = elo_dir / "elo.json"
    elo_path.write_text(json.dumps(ratings_by_game, indent=2) + "\n")

    return output_path
