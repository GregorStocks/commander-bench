"""Tests for leaderboard generation: OpenSkill ratings, placement, aggregation."""

import json
import tempfile
from pathlib import Path

from puppeteer.leaderboard import (
    capitalize_provider,
    compute_ratings,
    derive_display_name,
    extract_placements,
    generate_leaderboard,
    generate_leaderboard_file,
    load_model_registry,
)


def _make_game(
    game_id: str,
    timestamp: str,
    winner: str | None,
    players: list[dict],
) -> dict:
    return {
        "id": game_id,
        "timestamp": timestamp,
        "totalTurns": 10,
        "winner": winner,
        "players": players,
    }


def _pilot(name: str, model: str, cost: float = 1.0, placement: int | None = None) -> dict:
    d: dict = {"name": name, "type": "pilot", "model": model, "totalCostUsd": cost}
    if placement is not None:
        d["placement"] = placement
    return d


def _cpu(name: str) -> dict:
    return {"name": name, "type": "cpu", "commander": "Some Commander"}


# --- capitalize_provider ---


def test_capitalize_provider_known():
    assert capitalize_provider("anthropic") == "Anthropic"
    assert capitalize_provider("google") == "Google"
    assert capitalize_provider("openai") == "OpenAI"
    assert capitalize_provider("mistralai") == "Mistral AI"
    assert capitalize_provider("deepseek") == "DeepSeek"


def test_capitalize_provider_fallback():
    assert capitalize_provider("newprovider") == "Newprovider"


# --- derive_display_name ---


def test_derive_display_name():
    assert derive_display_name("mistralai/devstral-small") == "Devstral Small"
    assert derive_display_name("openai/gpt-4.1-mini") == "Gpt 4.1 Mini"


def test_derive_display_name_no_slash():
    assert derive_display_name("standalone") == "Standalone"


# --- load_model_registry ---


def test_load_model_registry():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(
            {
                "models": [
                    {"id": "anthropic/claude-sonnet-4.5", "name": "Claude Sonnet 4.5"},
                    {"id": "google/gemini-2.5-flash", "name": "Gemini 2.5 Flash"},
                ]
            },
            f,
        )
        path = Path(f.name)

    try:
        registry = load_model_registry(path)
        assert registry == {
            "anthropic/claude-sonnet-4.5": "Claude Sonnet 4.5",
            "google/gemini-2.5-flash": "Gemini 2.5 Flash",
        }
    finally:
        path.unlink()


def test_load_model_registry_missing_file():
    assert load_model_registry(Path("/nonexistent/models.json")) == {}


# --- extract_placements ---


def test_extract_placements_from_player_field():
    game = _make_game(
        "g1",
        "20260101_000000",
        "Alice",
        [
            _pilot("Alice", "a/x", placement=1),
            _pilot("Bob", "b/y", placement=2),
            _pilot("Carol", "c/z", placement=3),
        ],
    )
    result = extract_placements(game)
    assert result == {"Alice": 1, "Bob": 2, "Carol": 3}


def test_extract_placements_from_winner_only():
    """When no placement field and no game files, uses winner field."""
    game = _make_game(
        "g1",
        "20260101_000000",
        "Alice",
        [_pilot("Alice", "a/x"), _pilot("Bob", "b/y")],
    )
    result = extract_placements(game)
    assert result["Alice"] == 1
    assert result["Bob"] == 2


def test_extract_placements_no_winner():
    game = _make_game(
        "g1",
        "20260101_000000",
        None,
        [_pilot("Alice", "a/x"), _pilot("Bob", "b/y")],
    )
    result = extract_placements(game)
    assert result == {}


def test_extract_placements_from_game_file():
    """Falls back to reading full game JSON for elimination order."""
    with tempfile.TemporaryDirectory() as tmpdir:
        games_dir = Path(tmpdir)
        game_data = {
            "actions": [
                {"seq": 100, "message": "Carol has lost the game."},
                {"seq": 200, "message": "Bob has lost the game."},
            ]
        }
        (games_dir / "g1.json").write_text(json.dumps(game_data))

        game = _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [_pilot("Alice", "a/x"), _pilot("Bob", "b/y"), _pilot("Carol", "c/z")],
        )
        result = extract_placements(game, games_dir)
        assert result == {"Alice": 1, "Bob": 2, "Carol": 3}


# --- compute_ratings ---


def test_ratings_no_games():
    ratings, per_game = compute_ratings([])
    assert ratings == {}
    assert per_game == []


def test_ratings_winner_gains():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [
                _pilot("Alice", "a/model-a", placement=1),
                _pilot("Bob", "b/model-b", placement=2),
                _pilot("Carol", "c/model-c", placement=3),
                _pilot("Dave", "d/model-d", placement=4),
            ],
        )
    ]
    ratings, _per_game = compute_ratings(games)
    # Winner should have highest rating
    assert ratings["a/model-a"] > ratings["b/model-b"]
    assert ratings["b/model-b"] > ratings["c/model-c"]
    assert ratings["c/model-c"] > ratings["d/model-d"]


def test_ratings_no_placements_no_change():
    """Games with no placement data should not change ratings."""
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            None,
            [_pilot("Alice", "a/model-a"), _pilot("Bob", "b/model-b")],
        )
    ]
    _ratings, per_game = compute_ratings(games)
    assert len(per_game) == 1
    # Ratings should be equal (both start the same, no update)
    assert per_game[0]["players"][0]["ratingBefore"] == per_game[0]["players"][0]["ratingAfter"]


def test_ratings_chronological_order():
    """Ratings should build up across games processed chronologically."""
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [_pilot("Alice", "a/x", placement=1), _pilot("Bob", "b/y", placement=2)],
        ),
        _make_game(
            "g2",
            "20260102_000000",
            "Alice",
            [_pilot("Alice", "a/x", placement=1), _pilot("Bob", "b/y", placement=2)],
        ),
    ]
    _ratings, per_game = compute_ratings(games)
    # After 2 wins, Alice should be higher than after 1 win
    assert per_game[1]["players"][0]["ratingBefore"] > per_game[0]["players"][0]["ratingBefore"]


def test_ratings_per_game_snapshots():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [_pilot("Alice", "a/x", placement=1), _pilot("Bob", "b/y", placement=2)],
        )
    ]
    _, per_game = compute_ratings(games)
    assert len(per_game) == 1
    assert per_game[0]["id"] == "g1"
    assert len(per_game[0]["players"]) == 2

    alice = next(p for p in per_game[0]["players"] if p["model"] == "a/x")
    assert alice["ratingAfter"] > alice["ratingBefore"]

    bob = next(p for p in per_game[0]["players"] if p["model"] == "b/y")
    assert bob["ratingAfter"] < bob["ratingBefore"]


def test_ratings_skips_non_pilots():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "CPU1",
            [_cpu("CPU1"), _pilot("Alice", "a/x")],
        )
    ]
    ratings, per_game = compute_ratings(games)
    assert "a/x" in ratings
    assert len(per_game[0]["players"]) == 1


def test_ratings_full_ordering():
    """Full ordering (1st through 4th) should produce differentiated ratings."""
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [
                _pilot("Alice", "a/a", placement=1),
                _pilot("Bob", "b/b", placement=2),
                _pilot("Carol", "c/c", placement=3),
                _pilot("Dave", "d/d", placement=4),
            ],
        )
    ]
    ratings, _ = compute_ratings(games)
    # Full ordering: each player should have a distinct rating
    sorted_by_rating = sorted(ratings.items(), key=lambda x: -x[1])
    assert sorted_by_rating[0][0] == "a/a"  # Alice won
    assert sorted_by_rating[1][0] == "b/b"  # Bob 2nd
    assert sorted_by_rating[2][0] == "c/c"  # Carol 3rd
    assert sorted_by_rating[3][0] == "d/d"  # Dave 4th


# --- generate_leaderboard ---


def test_generate_leaderboard_basic():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [
                _pilot("Alice", "a/model-a", cost=5.0, placement=1),
                _pilot("Bob", "b/model-b", cost=2.0, placement=2),
            ],
        ),
        _make_game(
            "g2",
            "20260102_000000",
            "Bob",
            [
                _pilot("Alice", "a/model-a", cost=3.0, placement=2),
                _pilot("Bob", "b/model-b", cost=1.0, placement=1),
            ],
        ),
    ]
    result, ratings_by_game = generate_leaderboard(games, {})
    assert result["totalGames"] == 2
    assert len(result["models"]) == 2

    # Both have 1 win in 2 games
    for m in result["models"]:
        assert m["gamesPlayed"] == 2
        assert m["winRate"] == 0.5

    assert "g1" in ratings_by_game
    assert "g2" in ratings_by_game


def test_generate_leaderboard_no_games():
    result, ratings_by_game = generate_leaderboard([], {})
    assert result["totalGames"] == 0
    assert result["models"] == []
    assert ratings_by_game == {}


def test_generate_leaderboard_no_pilots():
    games = [_make_game("g1", "20260101_000000", "CPU1", [_cpu("CPU1"), _cpu("CPU2")])]
    result, _ = generate_leaderboard(games, {})
    assert result["totalGames"] == 1
    assert result["models"] == []


def test_generate_leaderboard_skips_non_pilot():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [_pilot("Alice", "a/model-a", placement=1), _cpu("CPU1")],
        )
    ]
    result, _ = generate_leaderboard(games, {})
    assert len(result["models"]) == 1
    assert result["models"][0]["modelName"] == "Model A"


def test_generate_leaderboard_uses_registry():
    registry = {"a/model-a": "Fancy Model Name"}
    games = [_make_game("g1", "20260101_000000", None, [_pilot("Alice", "a/model-a")])]
    result, _ = generate_leaderboard(games, registry)
    assert result["models"][0]["modelName"] == "Fancy Model Name"


def test_generate_leaderboard_sorted_by_rating():
    games = [
        _make_game(
            "g1",
            "20260101_000000",
            "Alice",
            [
                _pilot("Alice", "a/winner", placement=1),
                _pilot("Bob", "b/loser", placement=2),
            ],
        )
    ]
    result, _ = generate_leaderboard(games, {})
    assert result["models"][0]["modelName"] == "Winner"
    assert result["models"][1]["modelName"] == "Loser"


def test_generate_leaderboard_missing_cost():
    player = {"name": "Alice", "type": "pilot", "model": "a/x"}
    games = [_make_game("g1", "20260101_000000", None, [player])]
    result, _ = generate_leaderboard(games, {})
    assert result["models"][0]["avgApiCost"] == 0.0


def test_generate_leaderboard_avg_cost():
    games = [
        _make_game("g1", "20260101_000000", None, [_pilot("A", "a/x", cost=10.0)]),
        _make_game("g2", "20260102_000000", None, [_pilot("A", "a/x", cost=20.0)]),
    ]
    result, _ = generate_leaderboard(games, {})
    assert result["models"][0]["avgApiCost"] == 15.0


# --- generate_leaderboard_file ---


def test_generate_leaderboard_file_integration():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        games_dir = root / "games"
        games_dir.mkdir()
        data_dir = root / "data"
        data_dir.mkdir()

        games = [
            _make_game(
                "g1",
                "20260101_000000",
                "Alice",
                [
                    _pilot("Alice", "anthropic/claude-sonnet-4.5", cost=5.0, placement=1),
                    _pilot("Bob", "google/gemini-2.5-flash", cost=1.0, placement=2),
                ],
            )
        ]
        (games_dir / "index.json").write_text(json.dumps(games))

        models_json = root / "models.json"
        models_json.write_text(
            json.dumps(
                {
                    "models": [
                        {"id": "anthropic/claude-sonnet-4.5", "name": "Claude Sonnet 4.5"},
                        {"id": "google/gemini-2.5-flash", "name": "Gemini 2.5 Flash"},
                    ]
                }
            )
        )

        output_path = generate_leaderboard_file(games_dir, data_dir, models_json)

        # Verify benchmark-results.json
        assert output_path.exists()
        result = json.loads(output_path.read_text())
        assert result["totalGames"] == 1
        assert len(result["models"]) == 2
        assert result["models"][0]["modelName"] == "Claude Sonnet 4.5"
        assert result["models"][0]["rating"] > result["models"][1]["rating"]

        # Verify elo.json
        elo_path = games_dir.parent / "data" / "elo.json"
        assert elo_path.exists()
        elo_data = json.loads(elo_path.read_text())
        assert "g1" in elo_data
        assert "anthropic/claude-sonnet-4.5" in elo_data["g1"]
        claude_elo = elo_data["g1"]["anthropic/claude-sonnet-4.5"]
        assert claude_elo["after"] > claude_elo["before"]


def test_generate_leaderboard_file_no_index():
    """When index.json doesn't exist, should produce empty results."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        games_dir = root / "games"
        games_dir.mkdir()
        data_dir = root / "data"

        output_path = generate_leaderboard_file(games_dir, data_dir, root / "models.json")

        result = json.loads(output_path.read_text())
        assert result["totalGames"] == 0
        assert result["models"] == []


def test_generate_leaderboard_file_with_game_fallback():
    """When index.json entries lack placement, reads full game JSONs."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        games_dir = root / "games"
        games_dir.mkdir()
        data_dir = root / "data"

        # Index entry without placement
        games = [
            _make_game(
                "g1",
                "20260101_000000",
                "Alice",
                [_pilot("Alice", "a/x"), _pilot("Bob", "b/y"), _pilot("Carol", "c/z")],
            )
        ]
        (games_dir / "index.json").write_text(json.dumps(games))

        # Full game JSON with elimination order
        game_data = {
            "actions": [
                {"seq": 100, "message": "Carol has lost the game."},
                {"seq": 200, "message": "Bob has lost the game."},
            ]
        }
        (games_dir / "g1.json").write_text(json.dumps(game_data))

        models_json = root / "models.json"
        models_json.write_text(json.dumps({"models": []}))

        output_path = generate_leaderboard_file(games_dir, data_dir, models_json)
        result = json.loads(output_path.read_text())

        # Alice won (1st), Bob eliminated last (2nd), Carol eliminated first (3rd)
        models_by_name = {m["modelName"]: m for m in result["models"]}
        assert models_by_name["X"]["rating"] > models_by_name["Y"]["rating"]
        assert models_by_name["Y"]["rating"] > models_by_name["Z"]["rating"]
