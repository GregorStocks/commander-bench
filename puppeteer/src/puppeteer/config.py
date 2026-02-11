"""Configuration for the AI harness."""

import json
import random
import sys
from dataclasses import dataclass, field
from pathlib import Path


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
class StallerPlayer:
    """Staller personality: pure Java, intentionally slow auto-responder."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


@dataclass
class SleepwalkerPlayer:
    """Sleepwalker personality: MCP-based, Python client controls via stdio."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


@dataclass
class ChatterboxPlayer:
    """Chatterbox personality: LLM-powered commentator, auto-plays, chats via LLM."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root
    model: str | None = None  # LLM model (e.g., "anthropic/claude-sonnet-4")
    base_url: str | None = None  # API base URL (e.g., "https://openrouter.ai/api/v1")
    system_prompt: str | None = None  # Custom system prompt


@dataclass
class PilotPlayer:
    """Pilot personality: LLM-powered strategic game player."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root
    model: str | None = None  # LLM model (e.g., "google/gemini-2.0-flash-001")
    base_url: str | None = None  # API base URL (e.g., "https://openrouter.ai/api/v1")
    system_prompt: str | None = None  # Custom system prompt
    max_interactions_per_turn: int | None = None  # Loop detection threshold (default 25 in Java)
    reasoning_effort: str | None = None  # OpenRouter reasoning effort: "low", "medium", "high"


@dataclass
class CpuPlayer:
    """XMage built-in COMPUTER_MAD AI."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


# Union type for all player types
Player = PotatoPlayer | StallerPlayer | SleepwalkerPlayer | ChatterboxPlayer | PilotPlayer | CpuPlayer | SkeletonPlayer


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
    log_dir: Path = field(default_factory=lambda: Path.home() / "mage-bench-logs")
    jvm_opens: str = "--add-opens=java.base/java.io=ALL-UNNAMED"
    # Enable XRender pipeline for Java 2D — GPU-accelerated rendering on Linux
    jvm_rendering: str = "-Dsun.java2d.xrender=true"

    @property
    def jvm_headless_opts(self) -> str:
        """JVM options for headless (non-GUI) processes."""
        opts = [self.jvm_opens]
        if sys.platform == "darwin":
            opts.append("-Dapple.awt.UIElement=true")
        return " ".join(opts)

    @property
    def run_tag(self) -> str:
        """Derive run tag from config filename for per-target last symlinks."""
        assert self.config_file is not None, "run_tag requires config_file to be set"
        name = self.config_file.name
        if name == "ai-harness-config.json":
            return "dumb"
        stem = self.config_file.stem
        prefix = "ai-harness-"
        suffix = "-config"
        if stem.startswith(prefix) and stem.endswith(suffix):
            return stem[len(prefix) : -len(suffix)]
        return stem

    # CLI options
    config_file: Path | None = None
    streaming: bool = False
    record: bool = False
    record_output: Path | None = None
    overlay: bool = True
    overlay_port: int = 17888
    overlay_host: str = "127.0.0.1"

    # Match timer settings (XMage enum names, e.g. "MIN__20", "SEC__10")
    match_time_limit: str = ""
    match_buffer_time: str = ""

    # Game format settings (passed to XMage via config JSON)
    game_type: str = ""  # e.g. "Two Player Duel", "Commander Free For All"
    deck_type: str = ""  # e.g. "Constructed - Legacy", "Variant Magic - Freeform Commander"

    # Runtime state (set during execution)
    port: int = 0
    timestamp: str = ""

    # Player lists by type
    potato_players: list[PotatoPlayer] = field(default_factory=list)
    staller_players: list[StallerPlayer] = field(default_factory=list)
    sleepwalker_players: list[SleepwalkerPlayer] = field(default_factory=list)
    chatterbox_players: list[ChatterboxPlayer] = field(default_factory=list)
    pilot_players: list[PilotPlayer] = field(default_factory=list)
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
            self.match_time_limit = data.get("matchTimeLimit", "")
            self.match_buffer_time = data.get("matchBufferTime", "")
            self.game_type = data.get("gameType", "")
            self.deck_type = data.get("deckType", "")
            for i, player in enumerate(data.get("players", [])):
                player_type = player.get("type", "")
                name = player.get("name", f"player-{i}")
                deck = player.get("deck")  # Optional deck path

                if player_type == "sleepwalker":
                    self.sleepwalker_players.append(SleepwalkerPlayer(name=name, deck=deck))
                elif player_type == "chatterbox":
                    self.chatterbox_players.append(
                        ChatterboxPlayer(
                            name=name,
                            deck=deck,
                            model=player.get("model"),
                            base_url=player.get("base_url"),
                            system_prompt=player.get("system_prompt"),
                        )
                    )
                elif player_type == "pilot":
                    self.pilot_players.append(
                        PilotPlayer(
                            name=name,
                            deck=deck,
                            model=player.get("model"),
                            base_url=player.get("base_url"),
                            system_prompt=player.get("system_prompt"),
                            reasoning_effort=player.get("reasoning_effort"),
                        )
                    )
                elif player_type == "potato":
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))
                elif player_type == "staller":
                    self.staller_players.append(StallerPlayer(name=name, deck=deck))
                elif player_type == "cpu":
                    self.cpu_players.append(CpuPlayer(name=name, deck=deck))
                elif player_type == "skeleton":
                    # Legacy: treat as potato for backwards compatibility
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))

    def get_players_config_json(self) -> str:
        """Serialize resolved player config to JSON for passing to observer/GUI client."""
        players = []
        for p in self.pilot_players:
            d = {"type": "pilot", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            if p.model:
                d["model"] = p.model
            players.append(d)
        for p in self.chatterbox_players:
            d = {"type": "chatterbox", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            if p.model:
                d["model"] = p.model
            players.append(d)
        for p in self.sleepwalker_players:
            d = {"type": "sleepwalker", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            players.append(d)
        for p in self.potato_players:
            d = {"type": "potato", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            players.append(d)
        for p in self.staller_players:
            d = {"type": "staller", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            players.append(d)
        for p in self.cpu_players:
            d = {"type": "cpu", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            players.append(d)
        for p in self.skeleton_players:
            d = {"type": "skeleton", "name": p.name}
            if p.deck:
                d["deck"] = p.deck
            players.append(d)
        if not players:
            return ""
        result: dict = {"players": players}
        if self.game_type:
            result["gameType"] = self.game_type
        if self.deck_type:
            result["deckType"] = self.deck_type
        return json.dumps(result, separators=(",", ":"))

    def resolve_random_decks(self, project_root: Path) -> None:
        """Replace any deck="random" with a randomly chosen Commander .dck file."""
        all_players = (
            self.potato_players
            + self.staller_players
            + self.sleepwalker_players
            + self.chatterbox_players
            + self.pilot_players
            + self.cpu_players
            + self.skeleton_players
        )
        if not any(p.deck == "random" for p in all_players):
            return

        commander_dir = project_root / "Mage.Client" / "release" / "sample-decks" / "Commander"
        decks = [p.relative_to(project_root) for p in commander_dir.rglob("*.dck")]
        if not decks:
            print("WARNING: No .dck files found in Commander directory, keeping 'random' as-is")
            return

        for player in all_players:
            if player.deck == "random":
                chosen = random.choice(decks)
                player.deck = str(chosen)
                print(f"Random deck for {player.name}: {chosen.name}")
