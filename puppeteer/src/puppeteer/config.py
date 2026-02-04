"""Configuration for the AI harness."""

import sys
from dataclasses import dataclass, field
from pathlib import Path
import json
from typing import Union


@dataclass
class SkeletonPlayer:
    """Legacy skeleton player (kept for backwards compatibility)."""
    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


@dataclass
class PotatoPlayer:
    """Potato personality: pure Java, auto-responds to everything (dumbest)."""
    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


@dataclass
class SleepwalkerPlayer:
    """Sleepwalker personality: MCP-based, Python client controls via stdio."""
    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


@dataclass
class CpuPlayer:
    """XMage built-in COMPUTER_MAD AI."""
    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


# Union type for all player types
Player = Union[PotatoPlayer, SleepwalkerPlayer, CpuPlayer, SkeletonPlayer]


@dataclass
class Config:
    """Harness configuration with sensible defaults."""

    # Hardcoded defaults
    server: str = "localhost"
    start_port: int = 17171
    user: str = "observer"
    password: str = ""
    server_wait: int = 90
    skeleton_delay: int = 5
    log_dir: Path = field(default_factory=lambda: Path(".context/ai-harness-logs"))
    jvm_opens: str = "--add-opens=java.base/java.io=ALL-UNNAMED"

    @property
    def jvm_headless_opts(self) -> str:
        """JVM options for headless (non-GUI) processes."""
        opts = [self.jvm_opens]
        if sys.platform == "darwin":
            opts.append("-Dapple.awt.UIElement=true")
        return " ".join(opts)

    # CLI options
    skip_compile: bool = False
    config_file: Path | None = None
    streaming: bool = False

    # Runtime state (set during execution)
    port: int = 0
    timestamp: str = ""

    # Player lists by type
    potato_players: list[PotatoPlayer] = field(default_factory=list)
    sleepwalker_players: list[SleepwalkerPlayer] = field(default_factory=list)
    cpu_players: list[CpuPlayer] = field(default_factory=list)

    # Legacy: kept for backwards compatibility
    skeleton_players: list[SkeletonPlayer] = field(default_factory=list)

    def load_skeleton_config(self) -> None:
        """Load player configuration from JSON file."""
        if self.config_file is None:
            # Try default locations in order
            candidates = [
                Path(".context/ai-harness-config.json"),  # User override
                Path("puppeteer/ai-harness-config.json"),  # Repo default
            ]
            for candidate in candidates:
                if candidate.exists():
                    self.config_file = candidate
                    break
            else:
                return

        if not self.config_file.exists():
            return

        with open(self.config_file) as f:
            data = json.load(f)
            for i, player in enumerate(data.get("players", [])):
                player_type = player.get("type", "")
                name = player.get("name", f"player-{i}")
                deck = player.get("deck")  # Optional deck path

                if player_type == "sleepwalker":
                    self.sleepwalker_players.append(SleepwalkerPlayer(name=name, deck=deck))
                elif player_type == "potato":
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))
                elif player_type == "cpu":
                    self.cpu_players.append(CpuPlayer(name=name, deck=deck))
                elif player_type == "skeleton":
                    # Legacy: treat as potato for backwards compatibility
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))
