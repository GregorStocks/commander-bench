"""Configuration for the AI harness."""

from dataclasses import dataclass, field
from pathlib import Path
import json


@dataclass
class SkeletonPlayer:
    name: str


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

    # CLI options
    skip_compile: bool = False
    config_file: Path | None = None
    streaming: bool = False

    # Runtime state (set during execution)
    port: int = 0
    timestamp: str = ""
    skeleton_players: list[SkeletonPlayer] = field(default_factory=list)

    def load_skeleton_config(self) -> None:
        """Load skeleton player configuration from JSON file."""
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
                if player.get("type") == "skeleton":
                    name = player.get("name", f"skeleton-{i}")
                    self.skeleton_players.append(SkeletonPlayer(name=name))
