"""Sleepwalker: MCP-based XMage player that plays automatically and sends occasional chat messages."""

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

CHAT_MESSAGE = "zzz"
ACTION_DELAY_SECS = 0.5
CHAT_INTERVAL_SECS = 5


async def run_sleepwalker(
    server: str,
    port: int,
    username: str,
    project_root: Path,
) -> None:
    """Run the sleepwalker client."""
    print(f"[sleepwalker] Starting for {username}@{server}:{port}")

    # Build JVM args for the skeleton
    jvm_args = " ".join([
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        f"-Dxmage.headless.server={server}",
        f"-Dxmage.headless.port={port}",
        f"-Dxmage.headless.username={username}",
        "-Dxmage.headless.personality=sleepwalker",
    ])

    # Set up environment
    env = os.environ.copy()
    env["MAVEN_OPTS"] = jvm_args

    server_params = StdioServerParameters(
        command="mvn",
        args=["-q", "exec:java"],
        cwd=str(project_root / "Mage.Client.Headless"),
        env=env,
    )

    print(f"[sleepwalker] Spawning skeleton client...")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            # Initialize MCP connection
            result = await session.initialize()
            print(f"[sleepwalker] MCP initialized: {result.serverInfo}")

            # List available tools
            tools = await session.list_tools()
            print(f"[sleepwalker] Available tools: {[t.name for t in tools.tools]}")

            last_chat_time = time.time()
            last_log_length = 0

            print(f"[sleepwalker] Entering main loop...")

            while True:
                try:
                    # Check for pending action
                    result = await session.call_tool("is_action_on_me", {})
                    status = json.loads(result.content[0].text)

                    if status.get("action_pending"):
                        action_type = status.get("action_type", "UNKNOWN")
                        message = status.get("message", "")
                        print(f"[sleepwalker] Action required: {action_type}")
                        if message:
                            print(f"[sleepwalker]   Message: {message}")

                        # Delay before taking action
                        await asyncio.sleep(ACTION_DELAY_SECS)

                        # Execute default action
                        result = await session.call_tool("take_action", {})
                        action_result = json.loads(result.content[0].text)
                        print(f"[sleepwalker]   Result: {action_result.get('action_taken', 'unknown')}")

                        # Print game log (only new entries since last check)
                        log_result = await session.call_tool("get_game_log", {"max_chars": 10000})
                        log_data = json.loads(log_result.content[0].text)
                        current_log = log_data.get("log", "")
                        total_length = log_data.get("total_length", 0)

                        # Print new log entries
                        if total_length > last_log_length:
                            # Get the new portion of the log
                            new_chars = total_length - last_log_length
                            if new_chars > 0 and len(current_log) >= new_chars:
                                new_log = current_log[-new_chars:]
                                if new_log.strip():
                                    print(f"[sleepwalker] === New Log Entries ===")
                                    print(new_log)
                                    print(f"[sleepwalker] ========================")
                            last_log_length = total_length

                    # Send periodic chat message
                    current_time = time.time()
                    if current_time - last_chat_time > CHAT_INTERVAL_SECS:
                        result = await session.call_tool("send_chat_message", {"message": CHAT_MESSAGE})
                        chat_result = json.loads(result.content[0].text)
                        if chat_result.get("success"):
                            print(f"[sleepwalker] Chat sent: {CHAT_MESSAGE}")
                        else:
                            print(f"[sleepwalker] Chat failed (no game active yet?)")
                        last_chat_time = current_time

                    await asyncio.sleep(0.1)  # 100ms poll interval

                except KeyboardInterrupt:
                    print(f"[sleepwalker] Interrupted, shutting down...")
                    break
                except Exception as e:
                    print(f"[sleepwalker] Error: {e}")
                    await asyncio.sleep(1)


def main() -> int:
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Sleepwalker MCP client for XMage")
    parser.add_argument("--server", default="localhost", help="XMage server address")
    parser.add_argument("--port", type=int, default=17171, help="XMage server port")
    parser.add_argument("--username", default="Sleepy", help="Player username")
    parser.add_argument("--project-root", type=Path, help="Project root directory")
    args = parser.parse_args()

    # Determine project root
    if args.project_root:
        project_root = args.project_root.resolve()
    else:
        # Default: assume we're in the puppeteer directory
        project_root = Path.cwd().resolve()
        # If we're in puppeteer/src/puppeteer, go up
        if project_root.name == "puppeteer" and project_root.parent.name == "src":
            project_root = project_root.parent.parent.parent
        elif project_root.name == "puppeteer":
            project_root = project_root.parent

    print(f"[sleepwalker] Project root: {project_root}")

    try:
        asyncio.run(run_sleepwalker(
            server=args.server,
            port=args.port,
            username=args.username,
            project_root=project_root,
        ))
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
