"""Tests for configuration dataclasses."""

import json
import tempfile
from pathlib import Path

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
