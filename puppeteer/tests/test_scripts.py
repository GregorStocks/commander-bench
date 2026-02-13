"""Tests for scripts/ Python rewrites."""

import importlib.util
import json
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

SCRIPTS_DIR = Path(__file__).resolve().parent.parent.parent / "scripts"


def _import_script(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / f"{name}.py")
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


list_issues = _import_script("list-issues")
claim_issue = _import_script("claim-issue")
worktree_setup = _import_script("worktree-setup")
import_deck = _import_script("import-deck")
import_metagame = _import_script("import-metagame")


# ===========================================================================
# list-issues
# ===========================================================================


class TestListIssues:
    def test_sorted_by_priority(self, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
        issues_dir = tmp_path / "issues"
        issues_dir.mkdir()
        (issues_dir / "bug-a.json").write_text(json.dumps({"title": "Bug A", "priority": 3}))
        (issues_dir / "bug-b.json").write_text(json.dumps({"title": "Bug B", "priority": 1}))
        (issues_dir / "bug-c.json").write_text(json.dumps({"title": "Bug C", "priority": 2}))

        with patch.object(list_issues, "ISSUES_DIR", issues_dir):
            list_issues.main()

        out = capsys.readouterr().out
        lines = out.strip().splitlines()
        assert len(lines) == 3
        assert lines[0].startswith("bug-b:")  # priority 1
        assert lines[1].startswith("bug-c:")  # priority 2
        assert lines[2].startswith("bug-a:")  # priority 3

    def test_output_format(self, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
        issues_dir = tmp_path / "issues"
        issues_dir.mkdir()
        (issues_dir / "my-issue.json").write_text(json.dumps({"title": "My Title", "priority": 2}))

        with patch.object(list_issues, "ISSUES_DIR", issues_dir):
            list_issues.main()

        out = capsys.readouterr().out.strip()
        assert out == "my-issue: 2\tMy Title"


# ===========================================================================
# claim-issue
# ===========================================================================


class TestClaimIssue:
    def test_list_claimed(self) -> None:
        mock_result = MagicMock()
        mock_result.returncode = 0
        mock_result.stdout = "<!-- claim: bug-a -->\n<!-- claim: bug-b -->\nsome other body\n"

        with patch.object(claim_issue, "run", return_value=mock_result):
            result = claim_issue.list_claimed()

        assert result == ["bug-a", "bug-b"]

    def test_list_claimed_empty(self) -> None:
        mock_result = MagicMock()
        mock_result.returncode = 0
        mock_result.stdout = "no claims here\n"

        with patch.object(claim_issue, "run", return_value=mock_result):
            result = claim_issue.list_claimed()

        assert result == []

    def test_list_claimed_gh_failure(self) -> None:
        mock_result = MagicMock()
        mock_result.returncode = 1
        mock_result.stdout = ""

        with patch.object(claim_issue, "run", return_value=mock_result):
            result = claim_issue.list_claimed()

        assert result == []

    def test_missing_issue_exits_2(self, tmp_path: Path) -> None:
        with (
            patch.object(claim_issue, "ISSUES_DIR", tmp_path),
            patch.object(sys, "argv", ["claim-issue.py", "nonexistent"]),
            pytest.raises(SystemExit, match="2"),
        ):
            claim_issue.main()

    def test_no_args_exits_2(self) -> None:
        with patch.object(sys, "argv", ["claim-issue.py"]), pytest.raises(SystemExit, match="2"):
            claim_issue.main()


# ===========================================================================
# worktree-setup
# ===========================================================================


class TestWorktreeSetup:
    def test_creates_symlinks(self, tmp_path: Path) -> None:
        project_root = tmp_path / "project"
        project_root.mkdir()
        shared_images = tmp_path / "shared-images"

        with (
            patch.object(worktree_setup, "PROJECT_ROOT", project_root),
            patch.object(worktree_setup, "SHARED_IMAGES", shared_images),
            patch.object(worktree_setup, "CLIENT_MODULES", ["Mod-A"]),
            patch("pathlib.Path.home", return_value=tmp_path),
        ):
            worktree_setup.main()

        # Shared dirs created
        assert shared_images.is_dir()
        assert (tmp_path / ".m2" / "build-cache").is_dir()

        # Symlink created
        link = project_root / "Mod-A" / "plugins" / "images"
        assert link.is_symlink()
        assert link.resolve() == shared_images.resolve()

    def test_existing_dir_moved(self, tmp_path: Path) -> None:
        project_root = tmp_path / "project"
        project_root.mkdir()
        shared_images = tmp_path / "shared-images"

        # Pre-existing images directory with a file
        mod_dir = project_root / "Mod-A" / "plugins" / "images"
        mod_dir.mkdir(parents=True)
        (mod_dir / "card.jpg").write_text("img data")

        with (
            patch.object(worktree_setup, "PROJECT_ROOT", project_root),
            patch.object(worktree_setup, "SHARED_IMAGES", shared_images),
            patch.object(worktree_setup, "CLIENT_MODULES", ["Mod-A"]),
            patch("pathlib.Path.home", return_value=tmp_path),
        ):
            worktree_setup.main()

        # File moved to shared location
        assert (shared_images / "card.jpg").read_text() == "img data"
        # Original replaced by symlink
        link = project_root / "Mod-A" / "plugins" / "images"
        assert link.is_symlink()

    def test_existing_symlink_untouched(self, tmp_path: Path) -> None:
        project_root = tmp_path / "project"
        project_root.mkdir()
        shared_images = tmp_path / "shared-images"
        shared_images.mkdir(parents=True)

        # Pre-existing symlink
        plugins_dir = project_root / "Mod-A" / "plugins"
        plugins_dir.mkdir(parents=True)
        (plugins_dir / "images").symlink_to(shared_images)

        with (
            patch.object(worktree_setup, "PROJECT_ROOT", project_root),
            patch.object(worktree_setup, "SHARED_IMAGES", shared_images),
            patch.object(worktree_setup, "CLIENT_MODULES", ["Mod-A"]),
            patch("pathlib.Path.home", return_value=tmp_path),
        ):
            worktree_setup.main()

        link = plugins_dir / "images"
        assert link.is_symlink()
        assert link.resolve() == shared_images.resolve()


# ===========================================================================
# import-deck
# ===========================================================================


class TestImportDeck:
    def test_parse_deck_text(self) -> None:
        text = "4 Lightning Bolt\n2 Mountain\n\n1 Pyroblast\n"
        cards = import_deck.parse_deck_text(text)
        assert cards == {
            "Lightning Bolt": [(4, False)],
            "Mountain": [(2, False)],
            "Pyroblast": [(1, True)],
        }

    def test_parse_empty(self) -> None:
        assert import_deck.parse_deck_text("") == {}

    def test_format_dck(self) -> None:
        cards = {
            "Lightning Bolt": [(4, False)],
            "Mountain": [(2, False)],
            "Pyroblast": [(1, True)],
        }
        resolved = {
            "Lightning Bolt": ("A25", "141"),
            "Mountain": ("UST", "215"),
            "Pyroblast": ("ICE", "212"),
        }
        main_lines, sb_lines = import_deck.format_dck(cards, resolved)
        assert len(main_lines) == 2
        assert len(sb_lines) == 1
        assert main_lines[0] == "4 [A25:141] Lightning Bolt"
        assert sb_lines[0] == "SB: 1 [ICE:212] Pyroblast"

    def test_format_dck_unresolved_skipped(self) -> None:
        cards = {"Unknown Card": [(1, False)]}
        main_lines, sb_lines = import_deck.format_dck(cards, {})
        assert main_lines == []
        assert sb_lines == []


# ===========================================================================
# import-metagame
# ===========================================================================


class TestImportMetagame:
    def test_clean_archetype_name_uuid(self) -> None:
        assert (
            import_metagame.clean_archetype_name("4c-reanimator-70c5fc5f-0149-4242-8b1c-dd0b72eeb297")
            == "4c-reanimator"
        )

    def test_clean_archetype_name_numeric(self) -> None:
        assert import_metagame.clean_archetype_name("death-s-shadow-472") == "death-s-shadow"

    def test_clean_archetype_name_noop(self) -> None:
        assert import_metagame.clean_archetype_name("sneak-and-show") == "sneak-and-show"

    def test_slug_to_title_case(self) -> None:
        assert import_metagame.slug_to_title_case("sneak-and-show") == "Sneak-And-Show"
        assert import_metagame.slug_to_title_case("4c-reanimator") == "4c-Reanimator"
