"""Tests for harness epoch inference."""

from puppeteer.harness_epoch import HARNESS_EPOCH, MIN_LEADERBOARD_EPOCH, infer_epoch


def test_explicit_epoch_takes_precedence():
    assert infer_epoch("game_20260210_074307", 5) == 5
    assert infer_epoch("game_20260214_120000", 1) == 1


def test_infer_epoch_1_pre_yield_until():
    assert infer_epoch("game_20260210_074307", None) == 1
    assert infer_epoch("game_20260210_234529", None) == 1
    assert infer_epoch("game_20260211_163225", None) == 1
    assert infer_epoch("game_20260212_003225", None) == 1


def test_infer_epoch_2_yield_until():
    assert infer_epoch("game_20260212_235907", None) == 2
    assert infer_epoch("game_20260213_000525", None) == 2
    assert infer_epoch("game_20260213_210454_g3", None) == 2
    assert infer_epoch("game_20260214_005111_g1", None) == 2


def test_infer_epoch_3_priority_blocking():
    assert infer_epoch("game_20260214_090914_g1", None) == 3
    assert infer_epoch("game_20260214_120000", None) == 3
    assert infer_epoch("game_20260214_195959", None) == 3


def test_infer_epoch_4_short_ids_batch_combat():
    assert infer_epoch("game_20260214_200000", None) == 4
    assert infer_epoch("game_20260214_210000", None) == 4
    assert infer_epoch("game_20260215_000000", None) == 4


def test_epoch_boundary_exact():
    """Boundary timestamps themselves map to the new epoch."""
    assert infer_epoch("game_20260212_224200", None) == 2
    assert infer_epoch("game_20260214_084000", None) == 3
    assert infer_epoch("game_20260214_200000", None) == 4


def test_constants():
    assert HARNESS_EPOCH >= MIN_LEADERBOARD_EPOCH
    assert MIN_LEADERBOARD_EPOCH >= 1
