"""Main harness orchestration."""

import argparse
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

from puppeteer.config import Config
from puppeteer.port import find_available_port, wait_for_port
from puppeteer.process_manager import ProcessManager
from puppeteer.xml_config import modify_server_config


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
    args = parser.parse_args()

    config = Config(
        skip_compile=args.skip_compile,
        config_file=args.config,
    )
    return config


def compile_project(project_root: Path) -> bool:
    """Compile the project using Maven."""
    print("Compiling project...")
    result = subprocess.run(
        [
            "mvn",
            "-q",
            "-DskipTests",
            "-pl",
            "Mage.Server,Mage.Client,Mage.Client.Headless",
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
    """Start the XMage server."""
    jvm_args = " ".join([
        config.jvm_opens,
        "-Dxmage.aiHarnessMode=true",
        "-Dxmage.testMode=true",
        "-Dxmage.skipUserStats=true",
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
    """Start a headless skeleton client."""
    jvm_args = " ".join([
        config.jvm_opens,
        f"-Dxmage.headless.server={config.server}",
        f"-Dxmage.headless.port={config.port}",
        f"-Dxmage.headless.username={name}",
    ])

    env = {"MAVEN_OPTS": jvm_args}

    return pm.start_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client.Headless",
        env=env,
        log_file=log_path,
    )


def main() -> int:
    """Main harness orchestration."""
    config = parse_args()
    project_root = Path.cwd().resolve()
    pm = ProcessManager()

    # Set timestamp
    config.timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # Create log directory (use absolute paths since subprocesses run from different dirs)
    log_dir = (project_root / config.log_dir).resolve()
    log_dir.mkdir(parents=True, exist_ok=True)
    config_dir = log_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)

    # Compile if needed
    if not config.skip_compile:
        if not compile_project(project_root):
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
        pm.cleanup()
        return 1

    print("Server is ready!")

    # Load skeleton player config and copy to .context/ for the Java client
    config.load_skeleton_config()
    if config.config_file:
        client_config_path = project_root / ".context" / "ai-harness-config.json"
        client_config_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(config.config_file, client_config_path)
        print(f"Using config: {config.config_file}")

    import time

    if config.skeleton_players:
        print(f"Starting {len(config.skeleton_players)} skeleton client(s)...")

        # Start GUI client first
        gui_proc = start_gui_client(pm, project_root, config, client_log)
        time.sleep(config.skeleton_delay)

        # Start skeleton clients
        for idx, player in enumerate(config.skeleton_players):
            skeleton_log = log_dir / f"skeleton_{idx}_{config.timestamp}.log"
            with open(last_txt, "a") as f:
                f.write(f"skeleton_{idx}_log={skeleton_log}\n")
            print(f"Skeleton {idx} log: {skeleton_log}")
            start_skeleton_client(pm, project_root, config, player.name, skeleton_log)

        # Wait for GUI client to exit
        gui_proc.wait()
    else:
        # No skeleton players, just start GUI client
        gui_proc = start_gui_client(pm, project_root, config, client_log)
        gui_proc.wait()

    # Cleanup on exit
    pm.cleanup()
    return 0
