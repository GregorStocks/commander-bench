"""Tests for harness helper functions."""

import json
import tempfile
from pathlib import Path
from unittest.mock import patch

from puppeteer.config import Config, PilotPlayer
from puppeteer.harness import _ensure_game_over_event, _missing_llm_api_keys, _write_error_log


def test_missing_llm_api_keys_none():
    """No LLM players means no missing keys."""
    config = Config()
    assert _missing_llm_api_keys(config) == []


def test_missing_llm_api_keys_missing():
    """A pilot player with no API key set should produce an error."""
    config = Config()
    config.pilot_players = [PilotPlayer(name="ace", model="test/model")]
    with patch.dict("os.environ", {}, clear=True):
        errors = _missing_llm_api_keys(config)
    assert len(errors) == 1
    assert "ace" in errors[0]
    assert "OPENROUTER_API_KEY" in errors[0]


def test_missing_llm_api_keys_present():
    """A pilot player with the API key set should produce no error."""
    config = Config()
    config.pilot_players = [PilotPlayer(name="ace", model="test/model")]
    with patch.dict("os.environ", {"OPENROUTER_API_KEY": "sk-test"}, clear=True):
        errors = _missing_llm_api_keys(config)
    assert errors == []


def test_ensure_game_over_event_already_present():
    """Should not duplicate the game_over event if it already exists."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(
            json.dumps({"ts": "2024-01-01T00:00:00", "type": "game_start"})
            + "\n"
            + json.dumps({"ts": "2024-01-01T00:05:00", "type": "game_over"})
            + "\n"
        )

        _ensure_game_over_event(game_dir)

        lines = events_file.read_text().strip().splitlines()
        game_over_count = sum(1 for line in lines if json.loads(line).get("type") == "game_over")
        assert game_over_count == 1


def test_ensure_game_over_event_appended():
    """Should append a game_over event if one is missing."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(json.dumps({"ts": "2024-01-01T00:00:00", "type": "game_start"}) + "\n")

        _ensure_game_over_event(game_dir)

        lines = events_file.read_text().strip().splitlines()
        assert len(lines) == 2
        last_event = json.loads(lines[-1])
        assert last_event["type"] == "game_over"
        assert last_event["reason"] == "timeout_or_killed"


def test_ensure_game_over_event_no_file():
    """Should create the file with a game_over event if it doesn't exist."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _ensure_game_over_event(game_dir)

        events_file = game_dir / "game_events.jsonl"
        assert events_file.exists()
        event = json.loads(events_file.read_text().strip())
        assert event["type"] == "game_over"


def test_write_error_log_combines():
    """Should combine per-player error logs into errors.log."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        (game_dir / "alice_errors.log").write_text("Error on turn 3\nBad mana\n")
        (game_dir / "bob_errors.log").write_text("Timeout\n")

        _write_error_log(game_dir)

        error_log = game_dir / "errors.log"
        assert error_log.exists()
        content = error_log.read_text()
        assert "[alice_errors] Error on turn 3" in content
        assert "[alice_errors] Bad mana" in content
        assert "[bob_errors] Timeout" in content


def test_write_error_log_empty():
    """Should write 'No errors detected.' when no error logs exist."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _write_error_log(game_dir)

        error_log = game_dir / "errors.log"
        assert error_log.exists()
        assert "No errors detected" in error_log.read_text()
