"""Tests for configuration dataclasses."""

import json
import tempfile
from pathlib import Path
from unittest.mock import patch

import pytest

from puppeteer.config import (
    Config,
    CpuPlayer,
    PilotPlayer,
    PotatoPlayer,
    _generate_player_name,
    _resolve_personality,
    _resolve_randoms,
    _validate_name_parts,
    load_models,
    load_personalities,
)


def test_config_defaults():
    config = Config()
    assert config.server == "localhost"
    assert config.start_port == 17171
    assert config.potato_players == []
    assert config.pilot_players == []


def test_config_load_players_from_json():
    config_data = {
        "players": [
            {"type": "potato", "name": "spud"},
            {"type": "cpu", "name": "skynet"},
            {"type": "pilot", "name": "ace", "model": "test/model"},
        ],
        "matchTimeLimit": "MIN__60",
        "gameType": "Two Player Duel",
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config_data, f)
        config_path = Path(f.name)

    try:
        config = Config(config_file=config_path)
        config.load_config()

        assert len(config.potato_players) == 1
        assert config.potato_players[0].name == "spud"
        assert len(config.cpu_players) == 1
        assert isinstance(config.cpu_players[0], CpuPlayer)
        assert len(config.pilot_players) == 1
        assert isinstance(config.pilot_players[0], PilotPlayer)
        assert config.match_time_limit == "MIN__60"
        assert config.game_type == "Two Player Duel"
    finally:
        config_path.unlink()


def test_player_dataclass_fields():
    player = PotatoPlayer(name="test")
    assert player.name == "test"
    assert player.deck is None

    player_with_deck = PotatoPlayer(name="test", deck="decks/test.dck")
    assert player_with_deck.deck == "decks/test.dck"


def test_get_players_config_json_roundtrip():
    """Load players from JSON, serialize back, verify structure is preserved."""
    config_data = {
        "players": [
            {"type": "potato", "name": "spud", "deck": "decks/burn.dck"},
            {"type": "pilot", "name": "ace", "model": "test/model", "deck": "decks/control.dck"},
            {"type": "cpu", "name": "skynet"},
        ],
        "gameType": "Two Player Duel",
        "deckType": "Constructed - Legacy",
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config_data, f)
        config_path = Path(f.name)

    try:
        config = Config(config_file=config_path)
        config.load_config()
        result = json.loads(config.get_players_config_json())

        assert result["gameType"] == "Two Player Duel"
        assert result["deckType"] == "Constructed - Legacy"
        names = [p["name"] for p in result["players"]]
        assert "spud" in names
        assert "ace" in names
        assert "skynet" in names
    finally:
        config_path.unlink()


def test_get_players_config_json_empty():
    """No players should return empty string."""
    config = Config()
    assert config.get_players_config_json() == ""


def test_legacy_skeleton_treated_as_potato():
    """Skeleton player type should be loaded as a PotatoPlayer."""
    config_data = {
        "players": [
            {"type": "skeleton", "name": "bones"},
        ],
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config_data, f)
        config_path = Path(f.name)

    try:
        config = Config(config_file=config_path)
        config.load_config()
        assert len(config.potato_players) == 1
        assert config.potato_players[0].name == "bones"
        assert isinstance(config.potato_players[0], PotatoPlayer)
    finally:
        config_path.unlink()


def test_config_default_player_name():
    """Players without a name should get 'player-{index}' as default."""
    config_data = {
        "players": [
            {"type": "potato"},
            {"type": "cpu"},
        ],
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config_data, f)
        config_path = Path(f.name)

    try:
        config = Config(config_file=config_path)
        config.load_config()
        assert config.potato_players[0].name == "player-0"
        assert config.cpu_players[0].name == "player-1"
    finally:
        config_path.unlink()


def test_run_tag_no_config_file_raises():
    config = Config()
    with pytest.raises(AssertionError, match="run_tag requires config_file"):
        _ = config.run_tag


def test_run_tag_dumb():
    config = Config(config_file=Path("configs/dumb.json"))
    assert config.run_tag == "dumb"


def test_run_tag_gauntlet():
    config = Config(config_file=Path("configs/gauntlet.json"))
    assert config.run_tag == "gauntlet"


def test_run_tag_frontier():
    config = Config(config_file=Path("configs/frontier.json"))
    assert config.run_tag == "frontier"


def test_run_tag_llm4():
    config = Config(config_file=Path("configs/llm4.json"))
    assert config.run_tag == "llm4"


def test_run_tag_legacy_dumb():
    config = Config(config_file=Path("configs/legacy-dumb.json"))
    assert config.run_tag == "legacy-dumb"


def test_run_tag_custom():
    config = Config(config_file=Path("custom-thing.json"))
    assert config.run_tag == "custom-thing"


# --- Personality tests ---

SAMPLE_PERSONALITIES = {
    "test-pal": {
        "model": "test/model-x",
        "name": "TestPal",
        "prompt_suffix": "You are very friendly.",
        "reasoning_effort": "high",
    },
    "test-villain": {
        "model": "test/model-y",
        "name": "TestVillain",
        "prompt_suffix": "You are evil.",
    },
}


def test_personality_resolves_model():
    """Personality should set model on player."""
    player = PilotPlayer(name="placeholder")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=False)
    assert player.model == "test/model-x"
    assert player.name == "TestPal"


def test_personality_player_override_wins():
    """Explicit model on player should beat personality default."""
    player = PilotPlayer(name="MyName", model="my/override")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=True)
    assert player.model == "my/override"
    assert player.name == "MyName"


def test_personality_unknown_raises():
    """Unknown personality name should raise ValueError."""
    player = PilotPlayer(name="test")
    player.personality = "nonexistent"
    with pytest.raises(ValueError, match="Unknown personality"):
        _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=True)


def test_personality_name_from_personality():
    """When no explicit name in JSON, personality name is used."""
    player = PilotPlayer(name="player-0")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=False)
    assert player.name == "TestPal"


def test_personality_explicit_name_overrides():
    """Explicit name in player JSON should beat personality name."""
    player = PilotPlayer(name="CustomName")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=True)
    assert player.name == "CustomName"


def test_personality_prompt_suffix():
    """prompt_suffix from personality should be stored on player."""
    player = PilotPlayer(name="placeholder")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=False)
    assert player.prompt_suffix == "You are very friendly."


def test_personality_reasoning_effort():
    """reasoning_effort from personality should fill in when not set on player."""
    player = PilotPlayer(name="placeholder")
    player.personality = "test-pal"
    _resolve_personality(player, SAMPLE_PERSONALITIES, had_explicit_name=False)
    assert player.reasoning_effort == "high"

    # Player-level override wins
    player2 = PilotPlayer(name="placeholder", reasoning_effort="low")
    player2.personality = "test-pal"
    _resolve_personality(player2, SAMPLE_PERSONALITIES, had_explicit_name=False)
    assert player2.reasoning_effort == "low"


def test_personality_missing_name_in_definition_raises():
    """Personality without a name field should raise when player has no explicit name."""
    bad_personalities = {"no-name": {"model": "test/model"}}
    player = PilotPlayer(name="player-0")
    player.personality = "no-name"
    with pytest.raises(ValueError, match="must have a 'name' field"):
        _resolve_personality(player, bad_personalities, had_explicit_name=False)


def test_personality_name_too_long_raises():
    """Name exceeding 14 chars should raise ValueError."""
    long_personalities = {"long": {"model": "test/m", "name": "ThisNameIsTooLong"}}
    player = PilotPlayer(name="placeholder")
    player.personality = "long"
    with pytest.raises(ValueError, match="3-14 characters"):
        _resolve_personality(player, long_personalities, had_explicit_name=False)


def test_load_personalities_from_file():
    """load_personalities should read a JSON file."""
    pdata = {"my-pal": {"model": "x/y", "name": "MyPal", "prompt_suffix": "hi"}}
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", prefix="personalities", delete=False, dir=tempfile.gettempdir()
    ) as pf:
        json.dump(pdata, pf)
        pf_path = Path(pf.name)

    # Create a fake config file in the same directory so load_personalities finds it
    config_path = pf_path.parent / "test-config.json"
    config_path.write_text("{}")

    try:
        # Rename personalities file to match expected name
        personalities_path = pf_path.parent / "personalities.json"
        pf_path.rename(personalities_path)
        result = load_personalities(config_path)
        assert "my-pal" in result
        assert result["my-pal"]["model"] == "x/y"
    finally:
        personalities_path.unlink(missing_ok=True)
        config_path.unlink(missing_ok=True)


def test_personality_end_to_end_config_load():
    """Full integration: config JSON with personality field loads correctly."""
    # Create a temp dir with both config and personalities files
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir_path = Path(tmpdir)

        personalities = {
            "test-hero": {
                "model": "test/hero-model",
                "name": "TheHero",
                "prompt_suffix": "You are heroic.",
                "reasoning_effort": "medium",
            }
        }
        (tmpdir_path / "personalities.json").write_text(json.dumps(personalities))

        config_data = {
            "players": [
                {"type": "pilot", "personality": "test-hero", "deck": "random"},
                {"type": "pilot", "name": "Override", "personality": "test-hero", "deck": "random"},
            ]
        }
        config_path = tmpdir_path / "config.json"
        config_path.write_text(json.dumps(config_data))

        config = Config(config_file=config_path)
        config.load_config()

        assert len(config.pilot_players) == 2

        # First player: name and model from personality
        p1 = config.pilot_players[0]
        assert p1.name == "TheHero"
        assert p1.model == "test/hero-model"
        assert p1.prompt_suffix == "You are heroic."
        assert p1.reasoning_effort == "medium"
        assert p1.personality == "test-hero"

        # Second player: explicit name overrides personality name
        p2 = config.pilot_players[1]
        assert p2.name == "Override"
        assert p2.model == "test/hero-model"
        assert p2.prompt_suffix == "You are heroic."


# --- Random resolution tests ---

SAMPLE_MODELS_DATA = {
    "models": [
        {"id": "test/model-a", "name": "Model A", "name_part": "ModA"},
        {"id": "test/model-b", "name": "Model B", "name_part": "ModB"},
        {"id": "test/model-c", "name": "Model C", "name_part": "ModC"},
    ],
    "random_pool": ["test/model-a", "test/model-b", "test/model-c"],
}

SAMPLE_PERSONALITIES_WITH_PARTS = {
    "hero": {
        "name": "TheHero",
        "name_part": "Hero",
        "model": "test/model-a",
        "prompt_suffix": "You are heroic.",
    },
    "chill": {
        "name_part": "Chill",
        "prompt_suffix": "You are chill.",
    },
    "nerd": {
        "name_part": "Nerd",
        "prompt_suffix": "You are nerdy.",
    },
}


def test_validate_name_parts_valid():
    """Valid name_part combos should not raise."""
    _validate_name_parts(SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)


def test_validate_name_parts_catches_overflow():
    """name_part combo > 14 chars should raise ValueError."""
    bad_personalities = {
        "longname": {"name_part": "TooLong!", "prompt_suffix": "hi"},
    }
    bad_models = {
        "models": [{"id": "test/m", "name": "M", "name_part": "Longish"}],
        "random_pool": ["test/m"],
    }
    # "Longish TooLong!" = 16 chars
    with pytest.raises(ValueError, match="Invalid name_part combinations"):
        _validate_name_parts(bad_personalities, bad_models)


def test_validate_name_parts_missing_model_in_pool():
    """random_pool model not in models list should raise."""
    bad_models = {
        "models": [],
        "random_pool": ["test/missing"],
    }
    with pytest.raises(ValueError, match="not found in models list"):
        _validate_name_parts(SAMPLE_PERSONALITIES_WITH_PARTS, bad_models)


def test_validate_name_parts_real_data():
    """Validate actual personalities.json x models.json name_part combos all fit."""
    personalities = load_personalities(None)
    models_data = load_models(None)
    if not personalities or not models_data:
        pytest.skip("personalities.json or models.json not found")
    # Should not raise — if it does, we have a real misconfiguration
    _validate_name_parts(personalities, models_data)


def test_generate_player_name():
    """Name should be '{model_part} {personality_part}'."""
    name = _generate_player_name("test/model-a", "hero", SAMPLE_MODELS_DATA, SAMPLE_PERSONALITIES_WITH_PARTS)
    assert name == "ModA Hero"


def test_generate_player_name_fallback():
    """Unknown model/personality should use fallback name_parts."""
    name = _generate_player_name("unknown/model", "unknown", SAMPLE_MODELS_DATA, SAMPLE_PERSONALITIES_WITH_PARTS)
    # Falls back to last part of model ID and personality key
    assert name == "model unknown"


def test_resolve_randoms_picks_personality_and_model():
    """Random resolution should pick concrete personality and model."""
    player = PilotPlayer(name="player-0", personality="random", model="random")
    players = [(player, False)]

    with patch("puppeteer.config.random.choice", side_effect=["hero", "test/model-b"]):
        _resolve_randoms(players, SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)

    assert player.personality == "hero"
    assert player.model == "test/model-b"
    assert player.prompt_suffix == "You are heroic."
    assert player.name == "ModB Hero"


def test_resolve_randoms_no_duplicate_personalities():
    """Multiple random players should get different personalities."""
    p1 = PilotPlayer(name="p0", personality="random", model="random")
    p2 = PilotPlayer(name="p1", personality="random", model="random")
    players = [(p1, False), (p2, False)]

    choices = ["hero", "test/model-a", "chill", "test/model-b"]
    with patch("puppeteer.config.random.choice", side_effect=choices):
        _resolve_randoms(players, SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)

    assert p1.personality != p2.personality
    assert p1.model != p2.model


def test_resolve_randoms_explicit_model_not_randomized():
    """Explicit model should not be replaced by random."""
    player = PilotPlayer(name="player-0", personality="random", model="test/model-c")
    players = [(player, False)]

    with patch("puppeteer.config.random.choice", return_value="nerd"):
        _resolve_randoms(players, SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)

    assert player.model == "test/model-c"
    assert player.personality == "nerd"
    assert player.name == "ModC Nerd"


def test_resolve_randoms_explicit_name_preserved():
    """Explicit name in config should not be overwritten."""
    player = PilotPlayer(name="MyCustom", personality="random", model="random")
    players = [(player, True)]  # had_explicit_name=True

    with patch("puppeteer.config.random.choice", side_effect=["hero", "test/model-a"]):
        _resolve_randoms(players, SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)

    assert player.name == "MyCustom"


def test_resolve_randoms_non_random_untouched():
    """Players with non-random personality/model should pass through normally."""
    player = PilotPlayer(name="player-0", personality="hero", model="test/model-a")
    players = [(player, False)]

    _resolve_randoms(players, SAMPLE_PERSONALITIES_WITH_PARTS, SAMPLE_MODELS_DATA)

    assert player.personality == "hero"
    assert player.model == "test/model-a"
    assert player.name == "TheHero"  # From personality name field


def test_random_end_to_end_config_load():
    """Full integration: config with random values resolves to concrete players."""
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir_path = Path(tmpdir)

        personalities = {
            "alpha": {
                "name_part": "Alpha",
                "prompt_suffix": "You are alpha.",
            },
            "beta": {
                "name_part": "Beta",
                "prompt_suffix": "You are beta.",
            },
        }
        (tmpdir_path / "personalities.json").write_text(json.dumps(personalities))

        models = {
            "models": [
                {"id": "test/fast", "name": "Fast", "name_part": "Fast"},
                {"id": "test/smart", "name": "Smart", "name_part": "Smart"},
            ],
            "random_pool": ["test/fast", "test/smart"],
        }
        (tmpdir_path / "models.json").write_text(json.dumps(models))

        config_data = {
            "matchTimeLimit": "MIN__60",
            "players": [
                {"type": "pilot", "personality": "random", "model": "random", "deck": "random"},
                {"type": "pilot", "personality": "random", "model": "random", "deck": "random"},
            ],
        }
        config_path = tmpdir_path / "config.json"
        config_path.write_text(json.dumps(config_data))

        with patch("puppeteer.config.random.choice", side_effect=["alpha", "test/fast", "beta", "test/smart"]):
            config = Config(config_file=config_path)
            config.load_config()

        assert len(config.pilot_players) == 2
        p1, p2 = config.pilot_players

        assert p1.personality == "alpha"
        assert p1.model == "test/fast"
        assert p1.name == "Fast Alpha"
        assert p1.prompt_suffix == "You are alpha."

        assert p2.personality == "beta"
        assert p2.model == "test/smart"
        assert p2.name == "Smart Beta"
        assert p2.prompt_suffix == "You are beta."
