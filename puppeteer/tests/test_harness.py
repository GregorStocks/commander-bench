"""Tests for harness helper functions."""

import json
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import patch

from puppeteer.config import Config, PilotPlayer
from puppeteer.harness import (
    _ensure_game_over_event,
    _git,
    _missing_llm_api_keys,
    _print_game_summary,
    _write_error_log,
)


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
    """Should append a game_over event with correct seq if one is missing."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(json.dumps({"ts": "2024-01-01T00:00:00", "seq": 42, "type": "game_start"}) + "\n")

        _ensure_game_over_event(game_dir)

        lines = events_file.read_text().strip().splitlines()
        assert len(lines) == 2
        last_event = json.loads(lines[-1])
        assert last_event["type"] == "game_over"
        assert last_event["reason"] == "observer_crashed"
        assert last_event["seq"] == 43


def test_ensure_game_over_event_observer_closed():
    """Exit code 0 should produce reason 'observer_closed'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(json.dumps({"ts": "2024-01-01T00:00:00", "seq": 10, "type": "game_start"}) + "\n")

        _ensure_game_over_event(game_dir, observer_exit_code=0)

        lines = events_file.read_text().strip().splitlines()
        assert len(lines) == 2
        last_event = json.loads(lines[-1])
        assert last_event["type"] == "game_over"
        assert last_event["reason"] == "observer_closed"
        assert "observer window closed" in last_event["message"]
        assert last_event["seq"] == 11


def test_ensure_game_over_event_observer_crashed():
    """Non-zero exit code should produce reason 'observer_crashed'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(json.dumps({"ts": "2024-01-01T00:00:00", "seq": 10, "type": "game_start"}) + "\n")

        _ensure_game_over_event(game_dir, observer_exit_code=1)

        lines = events_file.read_text().strip().splitlines()
        assert len(lines) == 2
        last_event = json.loads(lines[-1])
        assert last_event["type"] == "game_over"
        assert last_event["reason"] == "observer_crashed"
        assert "code 1" in last_event["message"]


def test_ensure_game_over_event_no_file():
    """Should create the file with a game_over event if it doesn't exist."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _ensure_game_over_event(game_dir)

        events_file = game_dir / "game_events.jsonl"
        assert events_file.exists()
        event = json.loads(events_file.read_text().strip())
        assert event["type"] == "game_over"
        assert event["seq"] == 1


def test_print_game_summary_from_events_jsonl(capsys):
    """CPU-only games should read the result from game_events.jsonl."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(
            json.dumps({"ts": "2024-01-01T00:05:00", "type": "game_over", "message": "Player1 wins"}) + "\n"
        )

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "Player1 wins" in output
        assert "did not finish" not in output


def test_print_game_summary_from_pilot_log(capsys):
    """Headless client logs take priority over game_events.jsonl."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        (game_dir / "ace_pilot.log").write_text("INFO Game over: Player1 won the game\n")

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "Player1 won the game" in output
        assert "did not finish" not in output


def test_print_game_summary_no_logs(capsys):
    """No logs at all should print 'did not finish'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "did not finish" in output


def test_print_game_summary_synthetic_game_over(capsys):
    """A synthetic game_over (timeout_or_killed) should still show 'did not finish'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(
            json.dumps(
                {
                    "ts": "2024-01-01T00:05:00",
                    "type": "game_over",
                    "message": "Game ended (no GAME_OVER received)",
                    "reason": "timeout_or_killed",
                }
            )
            + "\n"
        )

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "did not finish" in output


def test_print_game_summary_observer_closed(capsys):
    """observer_closed reason should show the interrupted message, not 'did not finish'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(
            json.dumps(
                {
                    "ts": "2024-01-01T00:05:00",
                    "type": "game_over",
                    "message": "Game interrupted (observer window closed)",
                    "reason": "observer_closed",
                }
            )
            + "\n"
        )

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "observer window closed" in output
        assert "did not finish" not in output


def test_print_game_summary_observer_crashed(capsys):
    """observer_crashed reason should show 'did not finish'."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        events_file = game_dir / "game_events.jsonl"
        events_file.write_text(
            json.dumps(
                {
                    "ts": "2024-01-01T00:05:00",
                    "type": "game_over",
                    "message": "Game ended (observer exited with code 1)",
                    "reason": "observer_crashed",
                }
            )
            + "\n"
        )

        _print_game_summary(game_dir)

        output = capsys.readouterr().out
        assert "did not finish" in output


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


def test_git_returns_output():
    """Should return stripped stdout from a successful git command."""
    with patch("puppeteer.harness.subprocess.check_output", return_value="  main\n") as mock:
        result = _git("rev-parse --abbrev-ref HEAD", Path("/fake"))
    assert result == "main"
    mock.assert_called_once_with(
        "git rev-parse --abbrev-ref HEAD",
        shell=True,
        cwd=Path("/fake"),
        stderr=subprocess.DEVNULL,
        text=True,
    )


def test_git_returns_empty_on_failure():
    """Should return empty string when git command fails."""
    with patch("puppeteer.harness.subprocess.check_output", side_effect=subprocess.CalledProcessError(1, "git")):
        result = _git("rev-parse HEAD", Path("/fake"))
    assert result == ""
