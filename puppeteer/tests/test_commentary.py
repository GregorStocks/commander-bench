"""Tests for commentary: game narrative parsing, prompt building, response parsing."""

import json

# Import under test — scripts/commentary.py is run via uv so it's on sys.path
# We import the module's functions directly.
import sys
import tempfile
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "scripts"))
from commentary import (
    GameNarrative,
    TurnEvents,
    _compact_board,
    _is_noise,
    _strip_html,
    build_messages,
    format_narrative,
    load_game_narrative,
    parse_commentary_response,
)


def _make_game_dir(
    tmpdir: Path,
    *,
    meta: dict | None = None,
    events: list[dict] | None = None,
    game_jsonl: list[dict] | None = None,
) -> Path:
    """Create a minimal game directory with meta and events."""
    game_dir = tmpdir / "game_20260101_120000"
    game_dir.mkdir()

    if meta is None:
        meta = {
            "game_type": "Two Player Duel",
            "deck_type": "Constructed - Standard",
            "players": [
                {"name": "Alice", "type": "pilot", "model": "test/model-a", "deck_path": "decks/Burn.dck"},
                {"name": "Bob", "type": "pilot", "model": "test/model-b", "deck_path": "decks/Control.dck"},
            ],
        }
    (game_dir / "game_meta.json").write_text(json.dumps(meta))

    if events is None:
        _f = "<font color='#20B2AA'>"
        _ef = "</font>"
        _fc = "<font color='#B0C4DE'>"
        _fy = "<font color='#F0E68C'>"
        events = [
            {"message": f"TURN 1 for {_f}Alice{_ef} (20 - 20)", "ts": "t1", "seq": 1, "type": "game_action"},
            {"message": f"{_f}Alice{_ef} plays {_fc}Mountain{_ef} [abc]", "ts": "t2", "seq": 2, "type": "game_action"},
            {
                "message": f"{_f}Alice{_ef} casts {_fy}Lightning Bolt{_ef} [def]",
                "ts": "t3",
                "seq": 3,
                "type": "game_action",
            },
            {
                "turn": 1,
                "phase": "PRECOMBAT_MAIN",
                "step": "PRECOMBAT_MAIN",
                "active_player": "Alice",
                "priority_player": "Alice",
                "players": [
                    {"name": "Alice", "life": 20, "hand_count": 6, "battlefield": [{"name": "Mountain"}]},
                    {"name": "Bob", "life": 17, "hand_count": 7, "battlefield": []},
                ],
                "stack": [],
                "ts": "t4",
                "seq": 4,
                "type": "state_snapshot",
            },
            {"message": f"TURN 2 for {_f}Bob{_ef} (20 - 17)", "ts": "t5", "seq": 5, "type": "game_action"},
            {"message": f"{_f}Bob{_ef} plays {_fc}Island{_ef} [ghi]", "ts": "t6", "seq": 6, "type": "game_action"},
            {
                "turn": 2,
                "phase": "PRECOMBAT_MAIN",
                "step": "PRECOMBAT_MAIN",
                "active_player": "Bob",
                "priority_player": "Bob",
                "players": [
                    {"name": "Alice", "life": 20, "hand_count": 5, "battlefield": [{"name": "Mountain"}]},
                    {"name": "Bob", "life": 17, "hand_count": 6, "battlefield": [{"name": "Island"}]},
                ],
                "stack": [],
                "ts": "t7",
                "seq": 7,
                "type": "state_snapshot",
            },
            {"message": f"{_f}Alice{_ef} has won the game", "ts": "t8", "seq": 8, "type": "game_action"},
            {"seq": 9, "message": "Player Alice is the winner", "type": "game_over"},
        ]

    source = game_dir / "game_events.jsonl"
    if game_jsonl is not None:
        source = game_dir / "game.jsonl"
        events = game_jsonl
    source.write_text("\n".join(json.dumps(e) for e in events) + "\n")

    return game_dir


def test_strip_html():
    html = "<font color='#20B2AA'>Alice</font> plays <font color='#B0C4DE'>Mountain</font> [abc]"
    assert _strip_html(html) == "Alice plays Mountain"
    assert _strip_html("no tags here") == "no tags here"
    assert _strip_html("<font color='red'>X</font> [1a2b3c]") == "X"


def test_is_noise():
    assert _is_noise("Alice draws a card")
    assert _is_noise("Bob skips Draw step")
    assert _is_noise("Alice's library is shuffled")
    assert _is_noise("Alice skip attack")
    assert not _is_noise("Alice casts Lightning Bolt")
    assert not _is_noise("Alice plays Mountain")
    assert not _is_noise("TURN 1 for Alice (20 - 20)")


def test_compact_board():
    snapshot = {
        "players": [
            {
                "name": "Alice",
                "life": 20,
                "hand_count": 6,
                "battlefield": [
                    {"name": "Mountain"},
                    {"name": "Goblin Guide", "power": "2", "toughness": "2"},
                ],
            },
            {"name": "Bob", "life": 17, "hand_count": 7, "battlefield": []},
        ]
    }
    result = _compact_board(snapshot)
    assert "Alice: 20 life" in result
    assert "Mountain" in result
    assert "Goblin Guide 2/2" in result
    assert "Bob: 17 life" in result
    assert "empty board" in result


def test_load_game_narrative():
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = _make_game_dir(Path(tmpdir))
        narrative = load_game_narrative(game_dir)

        assert narrative.game_id == "game_20260101_120000"
        assert narrative.game_type == "Two Player Duel"
        assert narrative.deck_type == "Constructed - Standard"
        assert len(narrative.players) == 2
        assert narrative.players[0]["name"] == "Alice"
        assert narrative.players[1]["name"] == "Bob"
        assert narrative.winner == "Alice"
        assert len(narrative.turns) == 2
        assert narrative.turns[0].turn_number == 1
        assert narrative.turns[0].active_player == "Alice"
        assert narrative.turns[1].turn_number == 2
        assert narrative.turns[1].active_player == "Bob"


def test_load_game_narrative_actions_filtered():
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = _make_game_dir(Path(tmpdir))
        narrative = load_game_narrative(game_dir)

        # Turn 1 should have "plays Mountain" and "casts Lightning Bolt" but NOT the TURN line
        turn1_actions = narrative.turns[0].actions
        assert any("plays" in a and "Mountain" in a for a in turn1_actions)
        assert any("casts" in a and "Lightning Bolt" in a for a in turn1_actions)
        # TURN line is consumed as a boundary, not an action
        assert not any("TURN 1" in a for a in turn1_actions)


def test_load_game_narrative_board_summary():
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = _make_game_dir(Path(tmpdir))
        narrative = load_game_narrative(game_dir)

        # Turn 1 should have a board summary from the snapshot
        assert narrative.turns[0].board_summary
        assert "Alice" in narrative.turns[0].board_summary
        assert "20 life" in narrative.turns[0].board_summary


def test_load_game_narrative_with_reasoning():
    with tempfile.TemporaryDirectory() as tmpdir:
        _f = "<font color='#20B2AA'>"
        _ef = "</font>"
        _fy = "<font color='#F0E68C'>"
        reasoning_text = "I should bolt the opponent to pressure their life total early."
        events = [
            {"message": f"TURN 1 for {_f}Alice{_ef} (20 - 20)", "ts": "t1", "seq": 1, "type": "game_action"},
            {"message": f"{_f}Alice{_ef} casts {_fy}Lightning Bolt{_ef}", "ts": "t2", "seq": 2, "type": "game_action"},
            {"ts": "t2.5", "seq": 3, "type": "llm_response", "player": "Alice", "reasoning": reasoning_text},
            {
                "turn": 1,
                "phase": "PRECOMBAT_MAIN",
                "players": [
                    {"name": "Alice", "life": 20, "hand_count": 6, "battlefield": []},
                    {"name": "Bob", "life": 17, "hand_count": 7, "battlefield": []},
                ],
                "stack": [],
                "ts": "t3",
                "seq": 4,
                "type": "state_snapshot",
            },
        ]
        game_dir = _make_game_dir(Path(tmpdir), game_jsonl=events)
        narrative = load_game_narrative(game_dir)

        assert narrative.turns[0].reasoning.get("Alice") == reasoning_text


def test_load_game_narrative_fallback_no_game_jsonl():
    """When game.jsonl doesn't exist, falls back to game_events.jsonl."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = _make_game_dir(Path(tmpdir))
        # game_events.jsonl was created by _make_game_dir, game.jsonl was not
        assert not (game_dir / "game.jsonl").exists()
        assert (game_dir / "game_events.jsonl").exists()

        narrative = load_game_narrative(game_dir)
        assert len(narrative.turns) == 2
        assert narrative.winner == "Alice"


def test_load_game_narrative_chat():
    with tempfile.TemporaryDirectory() as tmpdir:
        _f = "<font color='#20B2AA'>"
        _ef = "</font>"
        events = [
            {"message": f"TURN 1 for {_f}Alice{_ef} (20 - 20)", "ts": "t1", "seq": 1, "type": "game_action"},
            {"from": "Alice", "message": "Hello there!", "ts": "t2", "seq": 2, "type": "player_chat"},
            {"from": "Bob", "message": "Good luck&#33;", "ts": "t3", "seq": 3, "type": "player_chat"},
            {
                "turn": 1,
                "phase": "PRECOMBAT_MAIN",
                "players": [],
                "stack": [],
                "ts": "t4",
                "seq": 4,
                "type": "state_snapshot",
            },
        ]
        game_dir = _make_game_dir(Path(tmpdir), game_jsonl=events)
        narrative = load_game_narrative(game_dir)

        assert len(narrative.turns[0].chat) == 2
        assert narrative.turns[0].chat[0] == ("Alice", "Hello there!")
        # HTML entities should be unescaped
        assert narrative.turns[0].chat[1] == ("Bob", "Good luck!")


def test_build_messages():
    narrative = GameNarrative(
        game_id="game_test",
        game_type="Two Player Duel",
        deck_type="Constructed - Standard",
        players=[{"name": "Alice", "model": "test/model", "deck_name": "Burn"}],
        winner="Alice",
        turns=[TurnEvents(turn_number=1, active_player="Alice", actions=["Alice casts Lightning Bolt"])],
    )
    messages = build_messages(narrative)
    assert len(messages) == 2
    assert messages[0]["role"] == "system"
    assert "commentator" in messages[0]["content"].lower()
    assert messages[1]["role"] == "user"
    assert "Alice" in messages[1]["content"]
    assert "Lightning Bolt" in messages[1]["content"]


def test_format_narrative():
    narrative = GameNarrative(
        game_id="game_test",
        game_type="Two Player Duel",
        deck_type="Constructed - Standard",
        players=[
            {"name": "Alice", "model": "test/model-a", "deck_name": "Burn"},
            {"name": "Bob", "model": "test/model-b", "deck_name": "Control"},
        ],
        winner="Alice",
        turns=[
            TurnEvents(
                turn_number=1,
                active_player="Alice",
                actions=["Alice plays Mountain", "Alice casts Lightning Bolt"],
                reasoning={"Alice": "Bolt face!"},
                chat=[("Alice", "Take that!")],
                board_summary="Alice: 20 life, [Mountain] | Bob: 17 life, empty board",
            ),
        ],
    )
    text = format_narrative(narrative)
    assert "Two Player Duel" in text
    assert "TURN 1 (Alice)" in text
    assert "Alice plays Mountain" in text
    assert "Alice casts Lightning Bolt" in text
    assert 'Alice was thinking: "Bolt face!"' in text
    assert '"Take that!"' in text
    assert "Winner: Alice" in text


def test_parse_commentary_response():
    response = json.dumps(
        [
            {"turn": 0, "text": "Welcome!"},
            {"turn": 1, "text": "Turn 1 action."},
            {"turn": -1, "text": "Game over!"},
        ]
    )
    entries = parse_commentary_response(response)
    assert len(entries) == 3
    assert entries[0]["turn"] == 0
    assert entries[2]["turn"] == -1


def test_parse_commentary_response_with_code_fence():
    response = '```json\n[{"turn": 0, "text": "Hello"}]\n```'
    entries = parse_commentary_response(response)
    assert len(entries) == 1
    assert entries[0]["text"] == "Hello"


def test_parse_commentary_response_invalid():
    with pytest.raises(json.JSONDecodeError):
        parse_commentary_response("not json at all")


def test_parse_commentary_response_missing_fields():
    with pytest.raises(AssertionError, match="Missing 'text' field"):
        parse_commentary_response('[{"turn": 1}]')


@pytest.mark.asyncio
async def test_generate_commentary():
    from commentary import generate_commentary

    # Mock the OpenAI client
    mock_response = MagicMock()
    mock_response.choices = [MagicMock()]
    mock_response.choices[0].message.content = json.dumps([{"turn": 0, "text": "Welcome!"}])
    mock_response.usage = MagicMock()
    mock_response.usage.prompt_tokens = 100
    mock_response.usage.completion_tokens = 50

    client = MagicMock()
    client.chat.completions.create = AsyncMock(return_value=mock_response)

    messages = [{"role": "system", "content": "test"}, {"role": "user", "content": "test"}]
    prices = {"test/model": (1.0, 2.0)}

    text, cost = await generate_commentary(client, "test/model", messages, prices)
    assert "Welcome!" in text
    assert cost > 0

    client.chat.completions.create.assert_called_once()
    call_kwargs = client.chat.completions.create.call_args[1]
    assert call_kwargs["model"] == "test/model"
