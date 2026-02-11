"""Tests for configuration dataclasses."""

import json
import tempfile
from pathlib import Path

import pytest

from puppeteer.config import ChatterboxPlayer, Config, CpuPlayer, PilotPlayer, PotatoPlayer


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
            {"type": "chatterbox", "name": "chatty", "model": "test/chat"},
        ],
        "matchTimeLimit": "MIN__20",
        "gameType": "Two Player Duel",
    }

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
        json.dump(config_data, f)
        config_path = Path(f.name)

    try:
        config = Config(config_file=config_path)
        config.load_skeleton_config()

        assert len(config.potato_players) == 1
        assert config.potato_players[0].name == "spud"
        assert len(config.cpu_players) == 1
        assert isinstance(config.cpu_players[0], CpuPlayer)
        assert len(config.pilot_players) == 1
        assert isinstance(config.pilot_players[0], PilotPlayer)
        assert len(config.chatterbox_players) == 1
        assert isinstance(config.chatterbox_players[0], ChatterboxPlayer)
        assert config.match_time_limit == "MIN__20"
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
        config.load_skeleton_config()
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


def test_skeleton_treated_as_potato():
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
        config.load_skeleton_config()
        assert len(config.potato_players) == 1
        assert config.potato_players[0].name == "bones"
        assert isinstance(config.potato_players[0], PotatoPlayer)
        assert len(config.skeleton_players) == 0
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
        config.load_skeleton_config()
        assert config.potato_players[0].name == "player-0"
        assert config.cpu_players[0].name == "player-1"
    finally:
        config_path.unlink()


def test_run_tag_no_config_file_raises():
    config = Config()
    with pytest.raises(AssertionError, match="run_tag requires config_file"):
        _ = config.run_tag


def test_run_tag_dumb():
    config = Config(config_file=Path("puppeteer/ai-harness-config.json"))
    assert config.run_tag == "dumb"


def test_run_tag_llm():
    config = Config(config_file=Path("puppeteer/ai-harness-llm-config.json"))
    assert config.run_tag == "llm"


def test_run_tag_llm4():
    config = Config(config_file=Path("puppeteer/ai-harness-llm4-config.json"))
    assert config.run_tag == "llm4"


def test_run_tag_legacy_dumb():
    config = Config(config_file=Path("puppeteer/ai-harness-legacy-dumb-config.json"))
    assert config.run_tag == "legacy-dumb"


def test_run_tag_legacy_llm():
    config = Config(config_file=Path("puppeteer/ai-harness-legacy-llm-config.json"))
    assert config.run_tag == "legacy-llm"


def test_run_tag_unknown_format():
    config = Config(config_file=Path("custom-thing.json"))
    assert config.run_tag == "custom-thing"
