"""Configuration for the puppeteer."""

import json
import random
import sys
from dataclasses import dataclass, field
from pathlib import Path


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
class PilotPlayer:
    """Pilot personality: LLM-powered strategic game player."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root
    model: str | None = None  # LLM model (e.g., "google/gemini-2.0-flash-001")
    base_url: str | None = None  # API base URL (e.g., "https://openrouter.ai/api/v1")
    system_prompt: str | None = None  # Custom system prompt
    max_interactions_per_turn: int | None = None  # Loop detection threshold (default 25 in Java)
    reasoning_effort: str | None = None  # OpenRouter reasoning effort: "low", "medium", "high"
    personality: str | None = None  # Named personality from personalities.json
    prompt_suffix: str | None = None  # Extra prompt text (set by personality resolution)


@dataclass
class CpuPlayer:
    """XMage built-in COMPUTER_MAD AI."""

    name: str
    deck: str | None = None  # Path to .dck file, relative to project root


# Union type for all player types
Player = PotatoPlayer | StallerPlayer | SleepwalkerPlayer | PilotPlayer | CpuPlayer

# XMage server username constraints (from Mage.Server/config/config.xml)
MIN_USERNAME_LENGTH = 3
MAX_USERNAME_LENGTH = 14


def load_personalities(config_file: Path | None) -> dict[str, dict]:
    """Load personality definitions from personalities.json.

    Search order: same directory as config_file, then puppeteer/ directory.
    Returns empty dict if no file found.
    """
    candidates: list[Path] = []
    if config_file is not None:
        candidates.append(config_file.parent / "personalities.json")
    candidates.append(Path("puppeteer/personalities.json"))

    for candidate in candidates:
        if candidate.exists():
            with open(candidate) as f:
                return json.load(f)
    return {}


def load_models(config_file: Path | None) -> dict:
    """Load model definitions from models.json.

    Search order: same directory as config_file, then puppeteer/ directory.
    Returns empty dict if no file found.
    """
    candidates: list[Path] = []
    if config_file is not None:
        candidates.append(config_file.parent / "models.json")
    candidates.append(Path("puppeteer/models.json"))

    for candidate in candidates:
        if candidate.exists():
            with open(candidate) as f:
                return json.load(f)
    return {}


def _validate_name_parts(personalities: dict[str, dict], models_data: dict) -> None:
    """Validate all random_pool x personality name_part combos fit XMage name limits.

    Called at startup when random resolution is needed. Fails fast with a clear
    error listing any offending combinations.
    """
    pool = models_data.get("random_pool", [])
    if not pool:
        return
    models_by_id = {m["id"]: m for m in models_data.get("models", [])}
    errors: list[str] = []
    for model_id in pool:
        model = models_by_id.get(model_id)
        if model is None:
            errors.append(f"random_pool model {model_id!r} not found in models list")
            continue
        if "name_part" not in model:
            errors.append(f"Model {model_id!r} missing name_part")
            continue
        m_part = model["name_part"]
        for p_key, p_data in personalities.items():
            p_part = p_data.get("name_part", p_key)
            name = f"{m_part} {p_part}"
            if len(name) < MIN_USERNAME_LENGTH or len(name) > MAX_USERNAME_LENGTH:
                errors.append(
                    f"{name!r} ({model_id} + {p_key}) is {len(name)} chars, "
                    f"must be {MIN_USERNAME_LENGTH}-{MAX_USERNAME_LENGTH}"
                )
    if errors:
        raise ValueError("Invalid name_part combinations:\n  " + "\n  ".join(errors))


def _generate_player_name(
    model_id: str,
    personality_key: str,
    models_data: dict,
    personalities: dict[str, dict],
) -> str:
    """Generate a player name from model name_part + personality name_part."""
    models_by_id = {m["id"]: m for m in models_data.get("models", [])}
    model = models_by_id.get(model_id, {})
    m_part = model.get("name_part", model_id.split("/")[-1][:6])
    p_data = personalities.get(personality_key, {})
    p_part = p_data.get("name_part", personality_key[:7])
    return f"{m_part} {p_part}"


def _resolve_randoms(
    players: list[tuple[PilotPlayer, bool]],
    personalities: dict[str, dict],
    models_data: dict,
) -> None:
    """Resolve 'random' personality/model values and apply personality defaults.

    Each player tuple is (player, had_explicit_name). Modifies players in-place.
    Avoids duplicate personalities and models across players.
    """
    has_randoms = any(p.personality == "random" or p.model == "random" for p, _ in players)
    if has_randoms:
        _validate_name_parts(personalities, models_data)

    pool_ids = models_data.get("random_pool", [])
    available_personalities = list(personalities.keys())
    available_models = list(pool_ids)

    used_personalities: set[str] = set()
    used_models: set[str] = set()

    for player, had_explicit_name in players:
        was_random_personality = player.personality == "random"

        # Resolve random personality
        if player.personality == "random":
            remaining = [k for k in available_personalities if k not in used_personalities]
            if not remaining:
                # All used — reset the pool (allows >15 players)
                used_personalities.clear()
                remaining = available_personalities
            chosen_p = random.choice(remaining)
            used_personalities.add(chosen_p)
            player.personality = chosen_p

        # Resolve random model
        if player.model == "random":
            remaining = [m for m in available_models if m not in used_models]
            if not remaining:
                used_models.clear()
                remaining = available_models
            chosen_m = random.choice(remaining)
            used_models.add(chosen_m)
            player.model = chosen_m

        # Generate name if needed (personality was random and no explicit name)
        if was_random_personality and not had_explicit_name:
            assert player.model is not None, "Model must be set before name generation"
            player.name = _generate_player_name(player.model, player.personality, models_data, personalities)
            # Mark as having a name so _resolve_personality skips the name requirement
            had_explicit_name = True

        _resolve_personality(player, personalities, had_explicit_name)


def _resolve_personality(
    player: PilotPlayer,
    personalities: dict[str, dict],
    had_explicit_name: bool,
) -> None:
    """Apply personality defaults to a player in-place. Player-level fields win."""
    if not player.personality:
        return

    pdata = personalities.get(player.personality)
    if pdata is None:
        raise ValueError(f"Unknown personality: {player.personality!r}. Available: {sorted(personalities.keys())}")

    # Name: player JSON name > personality name (required in personality)
    if not had_explicit_name:
        if "name" not in pdata:
            raise ValueError(f"Personality {player.personality!r} must have a 'name' field")
        player.name = pdata["name"]

    # Validate name length
    if len(player.name) < MIN_USERNAME_LENGTH or len(player.name) > MAX_USERNAME_LENGTH:
        raise ValueError(
            f"Player name {player.name!r} must be {MIN_USERNAME_LENGTH}-{MAX_USERNAME_LENGTH} "
            f"characters (XMage server limit)"
        )

    # Fill in defaults (player-level fields win)
    if player.model is None and "model" in pdata:
        player.model = pdata["model"]
    if player.base_url is None and "base_url" in pdata:
        player.base_url = pdata["base_url"]
    if hasattr(player, "reasoning_effort"):
        if player.reasoning_effort is None and "reasoning_effort" in pdata:
            player.reasoning_effort = pdata["reasoning_effort"]
    if player.prompt_suffix is None and "prompt_suffix" in pdata:
        player.prompt_suffix = pdata["prompt_suffix"]


_DECK_TYPE_TO_DIR: dict[str, str] = {
    "Variant Magic - Freeform Commander": "Commander",
    "Variant Magic - Commander": "Commander",
    "Constructed - Legacy": "Legacy",
    "Constructed - Modern": "Modern",
    "Constructed - Standard": "Standard",
}


@dataclass
class Config:
    """Puppeteer configuration with sensible defaults."""

    # Hardcoded defaults
    server: str = "localhost"
    start_port: int = 17171
    user: str = "spectator"
    password: str = ""
    server_wait: int = 90
    bridge_delay: int = 5
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
        return self.config_file.stem

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
    custom_start_life: int = 0  # 0 = use game type default

    # Post-game behavior
    skip_post_game_prompts: bool = False  # Skip YouTube/export prompts

    # Runtime state (set during execution)
    port: int = 0
    timestamp: str = ""

    # Player lists by type
    potato_players: list[PotatoPlayer] = field(default_factory=list)
    staller_players: list[StallerPlayer] = field(default_factory=list)
    sleepwalker_players: list[SleepwalkerPlayer] = field(default_factory=list)
    pilot_players: list[PilotPlayer] = field(default_factory=list)
    cpu_players: list[CpuPlayer] = field(default_factory=list)

    def load_config(self) -> None:
        """Load player configuration from JSON file."""
        if self.config_file is None:
            # Try default locations in order
            candidates = [
                Path(".context/ai-puppeteer-config.json"),  # User override
                Path("configs/dumb.json"),  # Repo default
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
            self.custom_start_life = data.get("customStartLife", 0)
            self.skip_post_game_prompts = data.get("skipPostGamePrompts", False)
            personalities = load_personalities(self.config_file)
            models_data = load_models(self.config_file)

            # First pass: construct player objects, collecting LLM players for random resolution
            llm_players: list[tuple[PilotPlayer, bool]] = []

            for i, player in enumerate(data.get("players", [])):
                player_type = player.get("type", "")
                has_explicit_name = "name" in player
                name = player.get("name", f"player-{i}")
                deck = player.get("deck")  # Optional deck path

                if player_type == "sleepwalker":
                    self.sleepwalker_players.append(SleepwalkerPlayer(name=name, deck=deck))
                elif player_type == "pilot":
                    p = PilotPlayer(
                        name=name,
                        deck=deck,
                        model=player.get("model"),
                        base_url=player.get("base_url"),
                        system_prompt=player.get("system_prompt"),
                        reasoning_effort=player.get("reasoning_effort"),
                        personality=player.get("personality"),
                    )
                    llm_players.append((p, has_explicit_name))
                    self.pilot_players.append(p)
                elif player_type == "potato":
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))
                elif player_type == "staller":
                    self.staller_players.append(StallerPlayer(name=name, deck=deck))
                elif player_type == "cpu":
                    self.cpu_players.append(CpuPlayer(name=name, deck=deck))
                elif player_type == "skeleton":
                    # Legacy: treat as potato for backwards compatibility
                    self.potato_players.append(PotatoPlayer(name=name, deck=deck))

            # Second pass: resolve random personalities/models and generate names
            _resolve_randoms(llm_players, personalities, models_data)

    def get_players_config_json(self) -> str:
        """Serialize resolved player config to JSON for passing to spectator/GUI client."""
        players = []
        for p in self.pilot_players:
            d = {"type": "pilot", "name": p.name}
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
        if not players:
            return ""
        result: dict = {"players": players}
        if self.game_type:
            result["gameType"] = self.game_type
        if self.deck_type:
            result["deckType"] = self.deck_type
        return json.dumps(result, separators=(",", ":"))

    def resolve_random_decks(self, project_root: Path) -> None:
        """Replace any deck="random" with a randomly chosen .dck file for the configured format."""
        all_players = (
            self.potato_players
            + self.staller_players
            + self.sleepwalker_players
            + self.pilot_players
            + self.cpu_players
        )
        if not any(p.deck == "random" for p in all_players):
            return

        dir_name = _DECK_TYPE_TO_DIR.get(self.deck_type, "Commander")
        deck_dir = project_root / "Mage.Client" / "release" / "sample-decks" / dir_name
        decks = [p.relative_to(project_root) for p in deck_dir.rglob("*.dck")]
        if not decks:
            print(f"WARNING: No .dck files found in {dir_name} directory, keeping 'random' as-is")
            return

        used: set[str] = set()
        for player in all_players:
            if player.deck == "random":
                available = [d for d in decks if str(d) not in used]
                if not available:
                    used.clear()
                    available = list(decks)
                chosen = random.choice(available)
                used.add(str(chosen))
                player.deck = str(chosen)
                print(f"Random deck for {player.name}: {chosen.name}")
