"""Main harness orchestration."""

import argparse
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from puppeteer.config import ChatterboxPlayer, PilotPlayer, Config
from puppeteer.port import can_bind_port, find_available_port, wait_for_port
from puppeteer.process_manager import ProcessManager
from puppeteer.xml_config import modify_server_config


def bring_to_foreground_macos() -> None:
    """Bring the Java app to foreground on macOS using AppleScript."""
    if sys.platform != "darwin":
        return

    import time
    time.sleep(2)  # Wait for window to appear

    subprocess.run(
        [
            "osascript", "-e",
            'tell application "System Events" to set frontmost of first process whose name contains "java" to true'
        ],
        capture_output=True,
    )


def parse_args() -> Config:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(description="XMage AI Harness")
    parser.add_argument(
        "--skip-compile",
        action="store_true",
        help="Skip Maven compilation",
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="Path to skeleton player config JSON",
    )
    parser.add_argument(
        "--streaming",
        action="store_true",
        help="Launch the streaming observer client (auto-requests hand permissions)",
    )
    parser.add_argument(
        "--record",
        nargs="?",
        const=True,
        default=False,
        metavar="PATH",
        help="Record game to video file (optionally specify output path)",
    )
    parser.add_argument(
        "--no-overlay",
        action="store_true",
        help="Disable local overlay server in streaming client",
    )
    parser.add_argument(
        "--overlay-port",
        type=int,
        default=17888,
        help="Local overlay server port (default: 17888)",
    )
    parser.add_argument(
        "--overlay-host",
        type=str,
        default="127.0.0.1",
        help="Local overlay bind host (default: 127.0.0.1)",
    )
    args = parser.parse_args()

    # Determine record output path
    record_output = None
    if args.record and args.record is not True:
        record_output = Path(args.record)

    config = Config(
        skip_compile=args.skip_compile,
        config_file=args.config,
        streaming=args.streaming,
        record=bool(args.record),
        record_output=record_output,
        overlay=not args.no_overlay,
        overlay_port=args.overlay_port,
        overlay_host=args.overlay_host,
    )
    return config


def compile_project(project_root: Path, streaming: bool = False) -> bool:
    """Compile the project using Maven."""
    print("Compiling project...")
    modules = "Mage.Server,Mage.Client,Mage.Client.Headless"
    if streaming:
        modules += ",Mage.Client.Streaming"

    result = subprocess.run(
        [
            "mvn",
            "-q",
            "-DskipTests",
            "-pl",
            modules,
            "-am",
            "install",
        ],
        cwd=project_root,
    )
    return result.returncode == 0


def refresh_streaming_resources(project_root: Path) -> bool:
    """Refresh streaming client resources under target/classes.

    This keeps overlay static files in sync even when --skip-compile is used.
    """
    result = subprocess.run(
        [
            "mvn",
            "-q",
            "-pl",
            "Mage.Client.Streaming",
            "resources:resources",
        ],
        cwd=project_root,
    )
    return result.returncode == 0


def find_available_overlay_port(start_port: int, max_attempts: int = 100) -> int:
    """Find a free local port for the overlay server."""
    for offset in range(max_attempts):
        port = start_port + offset
        if can_bind_port(port):
            return port
    raise RuntimeError(
        f"No available overlay port found in range {start_port}-{start_port + max_attempts - 1}"
    )


def start_server(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    config_path: Path,
    log_path: Path,
) -> subprocess.Popen:
    """Start the XMage server.

    Uses stock XMage server with testMode enabled, which provides:
    - Skipped password verification
    - Skipped deck validation
    - Extended idle timeouts
    - Skipped user stats operations
    """
    jvm_args = " ".join([
        config.jvm_headless_opts,
        "-Dxmage.testMode=true",
        f"-Dxmage.config.path={config_path}",
    ])

    env = {
        "XMAGE_AI_HARNESS": "1",
        "XMAGE_AI_HARNESS_USER": config.user,
        "XMAGE_AI_HARNESS_PASSWORD": config.password,
        "XMAGE_AI_HARNESS_SERVER": config.server,
        "XMAGE_AI_HARNESS_PORT": str(config.port),
        "XMAGE_AI_HARNESS_DISABLE_WHATS_NEW": "1",
        "MAVEN_OPTS": jvm_args,
    }

    return pm.start_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Server",
        env=env,
        log_file=log_path,
    )


def start_gui_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    log_path: Path,
) -> subprocess.Popen:
    """Start the GUI client."""
    # Pass resolved player config (with actual deck paths, not "random")
    config_json = config.get_players_config_json()

    jvm_args = " ".join([
        config.jvm_opens,
        "-Dxmage.aiHarness.autoConnect=true",
        "-Dxmage.aiHarness.autoStart=true",
        "-Dxmage.aiHarness.disableWhatsNew=true",
        f"-Dxmage.aiHarness.server={config.server}",
        f"-Dxmage.aiHarness.port={config.port}",
        f"-Dxmage.aiHarness.user={config.user}",
        f"-Dxmage.aiHarness.password={config.password}",
    ])

    env = {
        "XMAGE_AI_HARNESS": "1",
        "XMAGE_AI_HARNESS_USER": config.user,
        "XMAGE_AI_HARNESS_PASSWORD": config.password,
        "XMAGE_AI_HARNESS_SERVER": config.server,
        "XMAGE_AI_HARNESS_PORT": str(config.port),
        "XMAGE_AI_HARNESS_DISABLE_WHATS_NEW": "1",
        "XMAGE_AI_HARNESS_PLAYERS_CONFIG": config_json,
        "MAVEN_OPTS": jvm_args,
    }

    return pm.start_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client",
        env=env,
        log_file=log_path,
    )


def start_skeleton_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    deck_path: str | None,
    log_path: Path,
) -> subprocess.Popen:
    """Start a headless skeleton client (legacy, same as potato)."""
    return start_potato_client(pm, project_root, config, name, deck_path, log_path)


def start_potato_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    deck_path: str | None,
    log_path: Path,
    personality: str = "potato",
) -> subprocess.Popen:
    """Start an auto-responder headless client (potato/staller)."""
    jvm_args_list = [
        config.jvm_headless_opts,
        f"-Dxmage.headless.server={config.server}",
        f"-Dxmage.headless.port={config.port}",
        f"-Dxmage.headless.username={name}",
        f"-Dxmage.headless.personality={personality}",
    ]

    jvm_args = " ".join(jvm_args_list)
    env = {"MAVEN_OPTS": jvm_args}

    # Pass deck path as a Maven CLI arg (not in MAVEN_OPTS) because
    # MAVEN_OPTS gets shell-split by the mvn script, breaking paths with spaces.
    mvn_args = ["mvn", "-q"]
    if deck_path:
        resolved_path = project_root / deck_path
        mvn_args.append(f"-Dxmage.headless.deck={resolved_path}")
    mvn_args.append("exec:java")

    return pm.start_process(
        args=mvn_args,
        cwd=project_root / "Mage.Client.Headless",
        env=env,
        log_file=log_path,
    )


def start_sleepwalker_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    deck_path: str | None,
    log_path: Path,
) -> subprocess.Popen:
    """Start a sleepwalker client (Python MCP client + skeleton in MCP mode).

    This spawns the sleepwalker.py script which in turn spawns the skeleton.
    """
    import sys

    env = {
        "PYTHONUNBUFFERED": "1",
    }

    args = [
        sys.executable,
        "-m", "puppeteer.sleepwalker",
        "--server", config.server,
        "--port", str(config.port),
        "--username", name,
        "--project-root", str(project_root),
    ]

    if deck_path:
        args.extend(["--deck", str(project_root / deck_path)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_chatterbox_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    player: ChatterboxPlayer,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start a chatterbox client (LLM-powered MCP client + skeleton in MCP mode).

    This spawns the chatterbox.py script which in turn spawns the skeleton.
    """
    import os
    import sys

    env = {
        "PYTHONUNBUFFERED": "1",
    }

    # Pass API key from parent environment (never in config files)
    api_key = os.environ.get("OPENROUTER_API_KEY", "")
    if api_key:
        env["OPENROUTER_API_KEY"] = api_key

    args = [
        sys.executable,
        "-m", "puppeteer.chatterbox",
        "--server", config.server,
        "--port", str(config.port),
        "--username", player.name,
        "--project-root", str(project_root),
    ]

    if player.deck:
        args.extend(["--deck", str(project_root / player.deck)])
    if player.model:
        args.extend(["--model", player.model])
    if player.base_url:
        args.extend(["--base-url", player.base_url])
    if player.system_prompt:
        args.extend(["--system-prompt", player.system_prompt])
    if game_dir:
        args.extend(["--game-dir", str(game_dir)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_pilot_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    player: PilotPlayer,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start a pilot client (LLM-powered game player via MCP).

    This spawns the pilot.py script which in turn spawns the skeleton.
    """
    import os
    import sys

    env = {
        "PYTHONUNBUFFERED": "1",
    }

    # Pass API key from parent environment (never in config files)
    api_key = os.environ.get("OPENROUTER_API_KEY", "")
    if api_key:
        env["OPENROUTER_API_KEY"] = api_key

    args = [
        sys.executable,
        "-m", "puppeteer.pilot",
        "--server", config.server,
        "--port", str(config.port),
        "--username", player.name,
        "--project-root", str(project_root),
    ]

    if player.deck:
        args.extend(["--deck", str(project_root / player.deck)])
    if player.model:
        args.extend(["--model", player.model])
    if player.base_url:
        args.extend(["--base-url", player.base_url])
    if player.system_prompt:
        args.extend(["--system-prompt", player.system_prompt])
    if game_dir:
        args.extend(["--game-dir", str(game_dir)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_streaming_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start the streaming observer client.

    This client automatically requests hand permission from all players,
    making it suitable for Twitch streaming where viewers should see all hands.
    """
    # Pass resolved player config (with actual deck paths, not "random")
    config_json = config.get_players_config_json()

    jvm_args_list = [
        config.jvm_opens,
        "-Dxmage.aiHarness.autoConnect=true",
        "-Dxmage.aiHarness.autoStart=true",
        "-Dxmage.aiHarness.disableWhatsNew=true",
        f"-Dxmage.aiHarness.server={config.server}",
        f"-Dxmage.aiHarness.port={config.port}",
        f"-Dxmage.aiHarness.user={config.user}",
        f"-Dxmage.aiHarness.password={config.password}",
    ]

    # Add game directory for cost file polling
    if game_dir:
        jvm_args_list.append(f"-Dxmage.streaming.gameDir={game_dir}")

    # Add recording path if configured
    if config.record:
        resolved_game_dir = game_dir or (project_root / config.log_dir / f"game_{config.timestamp}").resolve()
        record_path = config.record_output or (resolved_game_dir / "recording.mov")
        jvm_args_list.append(f"-Dxmage.streaming.record={record_path}")

    # Add local overlay settings
    jvm_args_list.append(f"-Dxmage.streaming.overlay.enabled={'true' if config.overlay else 'false'}")
    jvm_args_list.append(f"-Dxmage.streaming.overlay.port={config.overlay_port}")
    jvm_args_list.append(f"-Dxmage.streaming.overlay.host={config.overlay_host}")

    jvm_args = " ".join(jvm_args_list)

    env = {
        "XMAGE_AI_HARNESS": "1",
        "XMAGE_AI_HARNESS_USER": config.user,
        "XMAGE_AI_HARNESS_PASSWORD": config.password,
        "XMAGE_AI_HARNESS_SERVER": config.server,
        "XMAGE_AI_HARNESS_PORT": str(config.port),
        "XMAGE_AI_HARNESS_DISABLE_WHATS_NEW": "1",
        "XMAGE_AI_HARNESS_PLAYERS_CONFIG": config_json,
        "MAVEN_OPTS": jvm_args,
    }

    return pm.start_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client.Streaming",
        env=env,
        log_file=log_path,
    )


def main() -> int:
    """Main harness orchestration."""
    config = parse_args()
    project_root = Path.cwd().resolve()
    pm = ProcessManager()

    try:
        # Set timestamp
        config.timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        # Recording requires streaming mode
        if config.record and not config.streaming:
            print("Recording requires streaming mode, enabling --streaming")
            config.streaming = True

        # Create log directory structure:
        #   .context/ai-harness-logs/          (top-level, persists across runs)
        #   .context/ai-harness-logs/game_TS/  (per-game directory)
        log_dir = (project_root / config.log_dir).resolve()
        log_dir.mkdir(parents=True, exist_ok=True)
        game_dir = log_dir / f"game_{config.timestamp}"
        game_dir.mkdir(parents=True, exist_ok=True)

        # Compile if needed
        if not config.skip_compile:
            if not compile_project(project_root, streaming=config.streaming):
                print("ERROR: Compilation failed")
                return 1

        if config.streaming:
            print("Refreshing streaming resources...")
            if not refresh_streaming_resources(project_root):
                print("ERROR: Failed to refresh streaming resources")
                return 1

        # Find available port
        print(f"Finding available port starting from {config.start_port}...")
        config.port = find_available_port(config.server, config.start_port)
        print(f"Using port {config.port}")

        # Pick an available overlay port for this run to support parallel observers.
        if config.streaming and config.overlay:
            requested_overlay_port = config.overlay_port
            config.overlay_port = find_available_overlay_port(requested_overlay_port)
            if config.overlay_port != requested_overlay_port:
                print(
                    f"Overlay port {requested_overlay_port} unavailable, using {config.overlay_port}"
                )

        # Generate server config into game directory
        server_config_path = game_dir / "server_config.xml"
        modify_server_config(
            source=project_root / "Mage.Server" / "config" / "config.xml",
            destination=server_config_path,
            port=config.port,
        )

        # Set up log paths (all inside game directory)
        server_log = game_dir / "server.log"
        observer_log = game_dir / "observer.log"

        # Update "last" symlink to point to this game directory
        last_link = log_dir / "last"
        last_link.unlink(missing_ok=True)
        last_link.symlink_to(game_dir.name)

        print(f"Game logs: {game_dir}")
        print(f"Server log: {server_log}")
        print(f"Observer log: {observer_log}")
        if config.record:
            record_path = config.record_output or (game_dir / "recording.mov")
            print(f"Recording to: {record_path}")
        if config.streaming and config.overlay:
            base = f"http://{config.overlay_host}:{config.overlay_port}"
            print(f"Overlay URL: {base}/")
            print(f"Overlay mock URL: {base}/?mock=1")
            print(f"Video overlay URL: {base}/video_overlay.html")

        # Start server
        print("Starting XMage server...")
        start_server(pm, project_root, config, server_config_path, server_log)

        if not wait_for_port(config.server, config.port, config.server_wait):
            print(f"ERROR: Server failed to start within {config.server_wait}s")
            print(f"Check {server_log} for details")
            return 1

        print("Server is ready!")

        # Load skeleton player config (passed to GUI client via environment variable)
        config.load_skeleton_config()
        if config.config_file:
            print(f"Using config: {config.config_file}")
            # Copy config into game directory for reference
            shutil.copy2(config.config_file, game_dir / "config.json")

        config.resolve_random_decks(project_root)

        import time

        # Choose which observer client to start (streaming or regular GUI)
        if config.streaming:
            print("Starting streaming observer client...")
            start_observer_client = start_streaming_client
        else:
            start_observer_client = start_gui_client

        # Count headless clients (sleepwalker, chatterbox, pilot, potato, legacy skeleton)
        headless_count = (
            len(config.sleepwalker_players) +
            len(config.chatterbox_players) +
            len(config.pilot_players) +
            len(config.potato_players) +
            len(config.staller_players) +
            len(config.skeleton_players)  # Legacy
        )

        # Start observer client first
        if config.streaming:
            observer_proc = start_observer_client(pm, project_root, config, observer_log, game_dir=game_dir)
        else:
            observer_proc = start_observer_client(pm, project_root, config, observer_log)

        # Bring the GUI window to the foreground on macOS
        bring_to_foreground_macos()

        if headless_count > 0:
            time.sleep(config.skeleton_delay)

            # Start sleepwalker clients (MCP-based, Python controls skeleton)
            for player in config.sleepwalker_players:
                log_path = game_dir / f"{player.name}_mcp.log"
                print(f"Sleepwalker ({player.name}) log: {log_path}")
                start_sleepwalker_client(pm, project_root, config, player.name, player.deck, log_path)

            # Start chatterbox clients (LLM-based, Python controls skeleton)
            for player in config.chatterbox_players:
                log_path = game_dir / f"{player.name}_llm.log"
                print(f"Chatterbox ({player.name}) log: {log_path}")
                start_chatterbox_client(pm, project_root, config, player, log_path, game_dir=game_dir)

            # Start pilot clients (LLM-based game player)
            for player in config.pilot_players:
                log_path = game_dir / f"{player.name}_pilot.log"
                print(f"Pilot ({player.name}) log: {log_path}")
                start_pilot_client(pm, project_root, config, player, log_path, game_dir=game_dir)

            # Start potato clients (pure Java, auto-responds)
            for player in config.potato_players:
                log_path = game_dir / f"{player.name}_mcp.log"
                print(f"Potato ({player.name}) log: {log_path}")
                start_potato_client(pm, project_root, config, player.name, player.deck, log_path)

            # Start staller clients (pure Java, intentionally slow auto-responders)
            for player in config.staller_players:
                log_path = game_dir / f"{player.name}_mcp.log"
                print(f"Staller ({player.name}) log: {log_path}")
                start_potato_client(
                    pm,
                    project_root,
                    config,
                    player.name,
                    player.deck,
                    log_path,
                    personality="staller",
                )

            # Start legacy skeleton clients (treated as potato)
            for player in config.skeleton_players:
                log_path = game_dir / f"{player.name}_mcp.log"
                print(f"Skeleton ({player.name}) log: {log_path}")
                start_skeleton_client(pm, project_root, config, player.name, player.deck, log_path)

            # Note: CPU players are handled by the GUI client/server

        # Wait for observer client to exit
        observer_proc.wait()

        return 0
    finally:
        # Always cleanup child processes, even on exceptions
        pm.cleanup()
