"""Main harness orchestration."""

import argparse
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from puppeteer.config import Config
from puppeteer.port import find_available_port, wait_for_port
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
    args = parser.parse_args()

    config = Config(
        skip_compile=args.skip_compile,
        config_file=args.config,
        streaming=args.streaming,
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
            "compile",
            "install",
        ],
        cwd=project_root,
    )
    return result.returncode == 0


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
    # Read config file content to pass via environment variable
    # (env vars handle special characters better than MAVEN_OPTS)
    config_json = ""
    if config.config_file and config.config_file.exists():
        import json
        with open(config.config_file) as f:
            config_data = json.load(f)
            config_json = json.dumps(config_data, separators=(',', ':'))

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
    log_path: Path,
) -> subprocess.Popen:
    """Start a headless skeleton client (legacy, same as potato)."""
    return start_potato_client(pm, project_root, config, name, log_path)


def start_potato_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    log_path: Path,
) -> subprocess.Popen:
    """Start a potato client (pure Java, auto-responds)."""
    jvm_args = " ".join([
        config.jvm_headless_opts,
        f"-Dxmage.headless.server={config.server}",
        f"-Dxmage.headless.port={config.port}",
        f"-Dxmage.headless.username={name}",
        "-Dxmage.headless.personality=potato",
    ])

    env = {"MAVEN_OPTS": jvm_args}

    return pm.start_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client.Headless",
        env=env,
        log_file=log_path,
    )


def start_sleepwalker_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    log_path: Path,
) -> subprocess.Popen:
    """Start a sleepwalker client (Python MCP client + skeleton in MCP mode).

    This spawns the sleepwalker.py script which in turn spawns the skeleton.
    """
    import sys

    env = {
        "PYTHONUNBUFFERED": "1",
    }

    return pm.start_process(
        args=[
            sys.executable,
            "-m", "puppeteer.sleepwalker",
            "--server", config.server,
            "--port", str(config.port),
            "--username", name,
            "--project-root", str(project_root),
        ],
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_streaming_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    log_path: Path,
) -> subprocess.Popen:
    """Start the streaming observer client.

    This client automatically requests hand permission from all players,
    making it suitable for Twitch streaming where viewers should see all hands.
    """
    # Read config file content to pass via environment variable
    config_json = ""
    if config.config_file and config.config_file.exists():
        import json
        with open(config.config_file) as f:
            config_data = json.load(f)
            config_json = json.dumps(config_data, separators=(',', ':'))

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

        # Create log directory (use absolute paths since subprocesses run from different dirs)
        log_dir = (project_root / config.log_dir).resolve()
        log_dir.mkdir(parents=True, exist_ok=True)
        config_dir = log_dir / "config"
        config_dir.mkdir(parents=True, exist_ok=True)

        # Compile if needed
        if not config.skip_compile:
            if not compile_project(project_root, streaming=config.streaming):
                print("ERROR: Compilation failed")
                return 1

        # Find available port
        print(f"Finding available port starting from {config.start_port}...")
        config.port = find_available_port(config.server, config.start_port)
        print(f"Using port {config.port}")

        # Generate server config
        server_config_path = config_dir / f"server_{config.timestamp}.xml"
        modify_server_config(
            source=project_root / "Mage.Server" / "config" / "config.xml",
            destination=server_config_path,
            port=config.port,
        )

        # Set up log paths
        server_log = log_dir / f"server_{config.timestamp}.log"
        client_log = log_dir / f"client_{config.timestamp}.log"

        # Write last.txt for easy reference
        last_txt = log_dir / "last.txt"
        with open(last_txt, "w") as f:
            f.write(f"server_log={server_log}\n")
            f.write(f"client_log={client_log}\n")
            f.write(f"server={config.server}\n")
            f.write(f"port={config.port}\n")

        print(f"Server log: {server_log}")
        print(f"Client log: {client_log}")

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

        import time

        # Choose which observer client to start (streaming or regular GUI)
        if config.streaming:
            print("Starting streaming observer client...")
            start_observer_client = start_streaming_client
        else:
            start_observer_client = start_gui_client

        # Count headless clients (sleepwalker, potato, legacy skeleton)
        headless_count = (
            len(config.sleepwalker_players) +
            len(config.potato_players) +
            len(config.skeleton_players)  # Legacy
        )

        # Start observer client first
        observer_proc = start_observer_client(pm, project_root, config, client_log)

        # Bring the GUI window to the foreground on macOS
        bring_to_foreground_macos()

        if headless_count > 0:
            time.sleep(config.skeleton_delay)
            client_idx = 0

            # Start sleepwalker clients (MCP-based, Python controls skeleton)
            for player in config.sleepwalker_players:
                client_log_path = log_dir / f"sleepwalker_{client_idx}_{config.timestamp}.log"
                with open(last_txt, "a") as f:
                    f.write(f"sleepwalker_{client_idx}_log={client_log_path}\n")
                print(f"Sleepwalker {client_idx} ({player.name}) log: {client_log_path}")
                start_sleepwalker_client(pm, project_root, config, player.name, client_log_path)
                client_idx += 1

            # Start potato clients (pure Java, auto-responds)
            for player in config.potato_players:
                client_log_path = log_dir / f"potato_{client_idx}_{config.timestamp}.log"
                with open(last_txt, "a") as f:
                    f.write(f"potato_{client_idx}_log={client_log_path}\n")
                print(f"Potato {client_idx} ({player.name}) log: {client_log_path}")
                start_potato_client(pm, project_root, config, player.name, client_log_path)
                client_idx += 1

            # Start legacy skeleton clients (treated as potato)
            for player in config.skeleton_players:
                client_log_path = log_dir / f"skeleton_{client_idx}_{config.timestamp}.log"
                with open(last_txt, "a") as f:
                    f.write(f"skeleton_{client_idx}_log={client_log_path}\n")
                print(f"Skeleton {client_idx} ({player.name}) log: {client_log_path}")
                start_skeleton_client(pm, project_root, config, player.name, client_log_path)
                client_idx += 1

            # Note: CPU players are handled by the GUI client/server

        # Wait for observer client to exit
        observer_proc.wait()

        return 0
    finally:
        # Always cleanup child processes, even on exceptions
        pm.cleanup()
