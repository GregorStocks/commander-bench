"""Tests for deck choice logic."""

import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from puppeteer.config import PilotPlayer
from puppeteer.deck_choice import (
    _build_choice_prompt,
    _deck_display_name,
    _parse_card_name,
    _parse_choice,
    _summarize_deck,
    list_available_decks,
    resolve_choice_decks,
)

# --- _parse_card_name ---


def test_parse_card_name_maindeck():
    count, name, sb = _parse_card_name("4 [M21:1] Lightning Bolt")
    assert count == 4
    assert name == "Lightning Bolt"
    assert sb is False


def test_parse_card_name_sideboard():
    count, name, sb = _parse_card_name("SB: 1 [FRF:87] Tasigur, the Golden Fang")
    assert count == 1
    assert name == "Tasigur, the Golden Fang"
    assert sb is True


def test_parse_card_name_unparseable():
    assert _parse_card_name("") is None
    assert _parse_card_name("# comment") is None
    assert _parse_card_name("NAME:Burn") is None


def test_parse_card_name_multiword_set():
    count, name, sb = _parse_card_name("2 [CSP:152] Snow-Covered Island")
    assert count == 2
    assert name == "Snow-Covered Island"
    assert sb is False


# --- _deck_display_name ---


def test_deck_display_name_simple():
    assert _deck_display_name(Path("Burn.dck")) == "Burn"


def test_deck_display_name_with_spaces():
    assert _deck_display_name(Path("Geoff's Daxos of Meletis.dck")) == "Geoff's Daxos of Meletis"


def test_deck_display_name_nested():
    assert _deck_display_name(Path("Commander/zurgo.dck")) == "zurgo"


# --- _summarize_deck ---


def test_summarize_deck_top_5():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".dck", delete=False) as f:
        f.write("4 [M21:1] Lightning Bolt\n")
        f.write("3 [M21:2] Goblin Guide\n")
        f.write("2 [M21:3] Eidolon of the Great Revel\n")
        f.write("2 [M21:4] Monastery Swiftspear\n")
        f.write("1 [M21:5] Searing Blaze\n")
        f.write("1 [M21:6] Lava Spike\n")
        f.write("10 [M21:7] Mountain\n")  # basic land, excluded
        path = Path(f.name)

    try:
        result = _summarize_deck(path)
        assert "Lightning Bolt" in result
        assert "Goblin Guide" in result
        assert "Eidolon of the Great Revel" in result
        assert "Monastery Swiftspear" in result
        # When counts tie, alphabetical order wins: Lava Spike before Searing Blaze
        assert "Lava Spike" in result
        assert "Searing Blaze" not in result  # 6th card, excluded
        assert "Mountain" not in result  # basic land
    finally:
        path.unlink()


def test_summarize_deck_excludes_basic_lands():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".dck", delete=False) as f:
        f.write("20 [M21:1] Island\n")
        f.write("10 [CSP:1] Snow-Covered Forest\n")
        f.write("4 [M21:2] Counterspell\n")
        path = Path(f.name)

    try:
        result = _summarize_deck(path)
        assert "Island" not in result
        assert "Snow-Covered Forest" not in result
        assert "4x Counterspell" in result
    finally:
        path.unlink()


def test_summarize_deck_excludes_sideboard():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".dck", delete=False) as f:
        f.write("4 [M21:1] Lightning Bolt\n")
        f.write("SB: 4 [M21:2] Pyroblast\n")
        path = Path(f.name)

    try:
        result = _summarize_deck(path)
        assert "Lightning Bolt" in result
        assert "Pyroblast" not in result
    finally:
        path.unlink()


# --- list_available_decks ---


def test_list_available_decks():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Legacy"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Burn.dck").write_text("4 [M21:1] Lightning Bolt\n")
        (deck_dir / "Delver.dck").write_text("4 [ISD:1] Delver of Secrets\n")

        result = list_available_decks(root, "Constructed - Legacy")
        assert len(result) == 2
        names = [name for _, name in result]
        assert names == ["Burn", "Delver"]  # sorted alphabetically


def test_list_available_decks_commander_default():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Zurgo.dck").write_text("1 [CMD:1] Sol Ring\n")

        result = list_available_decks(root, "")
        assert len(result) == 1
        assert result[0][1] == "Zurgo"


def test_list_available_decks_sorted():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Zurgo.dck").write_text("1 [CMD:1] Sol Ring\n")
        (deck_dir / "Alpha.dck").write_text("1 [CMD:1] Sol Ring\n")
        (deck_dir / "Middle.dck").write_text("1 [CMD:1] Sol Ring\n")

        result = list_available_decks(root, "Variant Magic - Commander")
        names = [name for _, name in result]
        assert names == ["Alpha", "Middle", "Zurgo"]


# --- _build_choice_prompt ---


def test_build_choice_prompt_small_pool():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Legacy"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Burn.dck").write_text("4 [M21:1] Lightning Bolt\n3 [M21:2] Goblin Guide\n")
        (deck_dir / "Delver.dck").write_text("4 [ISD:1] Delver of Secrets\n")

        decks = list_available_decks(root, "Constructed - Legacy")
        prompt = _build_choice_prompt(decks, root, "TestBot", [], "Constructed - Legacy")

        assert "TestBot" in prompt
        assert "Constructed - Legacy" in prompt
        assert "1. Burn" in prompt
        assert "2. Delver" in prompt
        # Small pool includes summaries
        assert "Lightning Bolt" in prompt
        assert "Delver of Secrets" in prompt
        assert "ONLY the number" in prompt


def test_build_choice_prompt_already_chosen():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Zurgo.dck").write_text("1 [CMD:1] Sol Ring\n")

        decks = list_available_decks(root, "Variant Magic - Commander")
        already = [("Player1", "Burn"), ("Player2", "Delver")]
        prompt = _build_choice_prompt(decks, root, "TestBot", already, "Variant Magic - Commander")

        assert "Player1: Burn" in prompt
        assert "Player2: Delver" in prompt


def test_build_choice_prompt_large_pool_no_summaries():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Legacy"
        deck_dir.mkdir(parents=True)
        for i in range(35):
            (deck_dir / f"Deck{i:02d}.dck").write_text(f"4 [M21:1] Card{i}\n")

        decks = list_available_decks(root, "Constructed - Legacy")
        prompt = _build_choice_prompt(decks, root, "TestBot", [], "Constructed - Legacy")

        # Large pool: names only, no card summaries
        assert "1. Deck00" in prompt
        assert "Card" not in prompt


# --- _parse_choice ---


def test_parse_choice_simple():
    assert _parse_choice("3", 5) == 2  # 0-based


def test_parse_choice_text_with_number():
    assert _parse_choice("I choose deck number 2.", 5) == 1


def test_parse_choice_first_number_wins():
    assert _parse_choice("I like 4 and 2", 5) == 3  # takes 4, 0-based = 3


def test_parse_choice_out_of_range_crashes():
    with pytest.raises(AssertionError, match="out of range"):
        _parse_choice("10", 5)


def test_parse_choice_zero_crashes():
    with pytest.raises(AssertionError, match="out of range"):
        _parse_choice("0", 5)


def test_parse_choice_no_number_crashes():
    with pytest.raises(AssertionError, match="No number found"):
        _parse_choice("I can't decide", 5)


# --- resolve_choice_decks ---


def test_resolve_choice_decks_sets_player_deck():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Alpha.dck").write_text("1 [CMD:1] Sol Ring\n")
        (deck_dir / "Beta.dck").write_text("1 [CMD:1] Command Tower\n")

        player = PilotPlayer(name="TestBot", deck="choice", model="test/model", base_url="https://openrouter.ai/api/v1")

        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "1"

        with (
            patch.dict("os.environ", {"OPENROUTER_API_KEY": "test-key"}),
            patch("puppeteer.deck_choice.OpenAI") as mock_openai,
        ):
            mock_client = MagicMock()
            mock_client.chat.completions.create.return_value = mock_response
            mock_openai.return_value = mock_client

            resolve_choice_decks([player], root, "Variant Magic - Commander")

        assert player.deck is not None
        assert player.deck.endswith(".dck")
        assert "Alpha" in player.deck


def test_resolve_choice_decks_no_duplicates():
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Alpha.dck").write_text("1 [CMD:1] Sol Ring\n")
        (deck_dir / "Beta.dck").write_text("1 [CMD:1] Command Tower\n")
        (deck_dir / "Gamma.dck").write_text("1 [CMD:1] Arcane Signet\n")

        p1 = PilotPlayer(name="Bot1", deck="choice", model="test/model", base_url="https://openrouter.ai/api/v1")
        p2 = PilotPlayer(name="Bot2", deck="choice", model="test/model", base_url="https://openrouter.ai/api/v1")

        # Both choose "1" — but after p1 picks Alpha, p2's pool is [Beta, Gamma]
        # so p2 choosing "1" gets Beta
        def make_response(text):
            resp = MagicMock()
            resp.choices = [MagicMock()]
            resp.choices[0].message.content = text
            return resp

        with (
            patch.dict("os.environ", {"OPENROUTER_API_KEY": "test-key"}),
            patch("puppeteer.deck_choice.OpenAI") as mock_openai,
        ):
            mock_client = MagicMock()
            mock_client.chat.completions.create.side_effect = [
                make_response("1"),
                make_response("1"),
            ]
            mock_openai.return_value = mock_client

            resolve_choice_decks([p1, p2], root, "Variant Magic - Commander")

        assert p1.deck != p2.deck
        assert "Alpha" in p1.deck
        assert "Beta" in p2.deck


def test_resolve_choice_decks_skips_non_choice():
    """Players with deck != 'choice' should be untouched."""
    p1 = PilotPlayer(name="Bot1", deck="random", model="test/model")
    p2 = PilotPlayer(name="Bot2", deck="some/path.dck", model="test/model")

    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Zurgo.dck").write_text("1 [CMD:1] Sol Ring\n")

        resolve_choice_decks([p1, p2], root, "Variant Magic - Commander")

    assert p1.deck == "random"
    assert p2.deck == "some/path.dck"


def test_resolve_choice_decks_no_model_crashes():
    """Player with deck='choice' but no model should crash."""
    with tempfile.TemporaryDirectory() as tmpdir:
        root = Path(tmpdir)
        deck_dir = root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        deck_dir.mkdir(parents=True)
        (deck_dir / "Zurgo.dck").write_text("1 [CMD:1] Sol Ring\n")

        player = PilotPlayer(name="NoModel", deck="choice")
        with pytest.raises(AssertionError, match="no model set"):
            resolve_choice_decks([player], root, "Variant Magic - Commander")
