"""Chatterbox: LLM-powered game commentator that auto-plays and chats based on game state."""

import argparse
import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from openai import AsyncOpenAI

from puppeteer.llm_cost import (
    DEFAULT_BASE_URL,
    get_model_price,
    load_prices,
    required_api_key_env,
    write_cost_file,
)


DEFAULT_MODEL = "google/gemini-2.0-flash-001"
MAX_TOKENS = 256
LLM_REQUEST_TIMEOUT_SECS = 45
MAX_CONSECUTIVE_TIMEOUTS = 3


def _log(msg: str) -> None:
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] {msg}")


def _log_error(game_dir: Path | None, username: str, msg: str) -> None:
    """Append an error line to {username}_errors.log in the game directory."""
    _log(msg)
    if game_dir:
        ts = datetime.now().strftime("%H:%M:%S")
        try:
            with open(game_dir / f"{username}_errors.log", "a") as f:
                f.write(f"[{ts}] {msg}\n")
        except OSError:
            pass


DEFAULT_SYSTEM_PROMPT = """\
You are a player in a Magic: The Gathering game. You don't control your own game \
decisions - they are handled automatically. Your job is to be an entertaining \
chatterbox who comments on the game via chat messages.

Your loop:
1. Call auto_pass_until_event to auto-handle all game actions and wait for something interesting
2. Read the new_log field in the result - it describes what changed (turns, life totals, board state)
3. Optionally call get_game_state to see the full board
4. Optionally send a chat message reacting to what happened (keep messages SHORT, under 80 chars)
5. Go back to step 1

Be funny, snarky, or dramatic. React to plays, comment on cards, trash talk opponents. \
You don't have to chat every time - sometimes just observing is fine. \
DO NOT explain rules or be helpful. Be a personality. Keep it brief.\
"""


def mcp_tools_to_openai(mcp_tools) -> list[dict]:
    """Convert MCP tool definitions to OpenAI function calling format."""
    return [
        {
            "type": "function",
            "function": {
                "name": tool.name,
                "description": tool.description or "",
                "parameters": tool.inputSchema or {"type": "object", "properties": {}},
            },
        }
        for tool in mcp_tools
    ]


async def execute_tool(session: ClientSession, name: str, arguments: dict) -> str:
    """Route a tool call through the MCP session and return the result text."""
    try:
        result = await session.call_tool(name, arguments)
        return result.content[0].text
    except Exception as e:
        return json.dumps({"error": str(e)})


async def run_llm_loop(
    session: ClientSession,
    client: AsyncOpenAI,
    model: str,
    system_prompt: str,
    tools: list[dict],
    username: str = "",
    game_dir: Path | None = None,
    prices: dict[str, tuple[float, float]] | None = None,
) -> None:
    """Run the LLM-driven agentic loop."""
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "The game is starting. Begin your loop: call auto_pass_until_event to wait for interesting game events."},
    ]
    calls_since_chat = 0
    model_price = get_model_price(model, prices or {})
    cumulative_cost = 0.0
    consecutive_timeouts = 0

    while True:
        try:
            response = await asyncio.wait_for(
                client.chat.completions.create(
                    model=model,
                    messages=messages,
                    tools=tools,
                    tool_choice="auto",
                    max_tokens=MAX_TOKENS,
                ),
                timeout=LLM_REQUEST_TIMEOUT_SECS,
            )
            consecutive_timeouts = 0
            choice = response.choices[0]

            # Track token usage and cost
            if response.usage and model_price is not None:
                input_cost = (response.usage.prompt_tokens or 0) * model_price[0] / 1_000_000
                output_cost = (response.usage.completion_tokens or 0) * model_price[1] / 1_000_000
                cumulative_cost += input_cost + output_cost
                if game_dir:
                    write_cost_file(game_dir, username, cumulative_cost)

            # If the LLM produced tool calls, process them
            if choice.message.tool_calls:
                messages.append(choice.message)

                chatted = False
                for tool_call in choice.message.tool_calls:
                    fn = tool_call.function
                    args = json.loads(fn.arguments) if fn.arguments else {}
                    _log(f"[chatterbox] Tool: {fn.name}({json.dumps(args, separators=(',', ':'))})")

                    result_text = await execute_tool(session, fn.name, args)

                    # Log interesting results
                    if fn.name == "send_chat_message":
                        chatted = True
                        msg = args.get("message", "")
                        result_data = json.loads(result_text)
                        if result_data.get("success"):
                            _log(f"[chatterbox] Chat sent: {msg}")
                        else:
                            _log_error(game_dir, username, f"[chatterbox] Chat failed: {result_text}")
                    elif fn.name == "auto_pass_until_event":
                        result_data = json.loads(result_text)
                        actions = result_data.get("actions_taken", 0)
                        new_log = result_data.get("new_log", "")
                        event = result_data.get("event_occurred", False)
                        _log(f"[chatterbox] Auto-pass: {actions} actions, {len(new_log)} new chars, event={event}")

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": result_text,
                    })

                if chatted:
                    calls_since_chat = 0
                else:
                    calls_since_chat += 1
            else:
                # LLM stopped calling tools - add its text response and prompt it to continue.
                # Skip empty/whitespace content (Gemini Flash returns these and then
                # chokes on the empty "parts" field in the next request).
                content = (choice.message.content or "").strip()
                if content:
                    _log(f"[chatterbox] LLM text: {content[:200]}")
                    messages.append({"role": "assistant", "content": content})
                messages.append({
                    "role": "user",
                    "content": "Continue your loop. Call auto_pass_until_event.",
                })
                calls_since_chat += 1

            # Nudge if the LLM has been quiet too long
            if calls_since_chat >= 8:
                messages.append({
                    "role": "user",
                    "content": "You've been quiet for a while! Send a chat message reacting to what's happening.",
                })
                calls_since_chat = 0

            # Trim message history to avoid unbounded growth.
            # Keep system prompt + a personality reminder + last messages.
            if len(messages) > 25:
                messages = (
                    [messages[0]]
                    + [{"role": "user", "content": "Remember: you're an entertaining chatterbox. React to plays with short, funny chat messages. Don't just silently observe."}]
                    + messages[-15:]
                )

        except asyncio.TimeoutError:
            consecutive_timeouts += 1
            _log_error(game_dir, username, f"[chatterbox] LLM request timed out after {LLM_REQUEST_TIMEOUT_SECS}s [{consecutive_timeouts}]")
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            if consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                _log("[chatterbox] Repeated LLM timeouts, resetting conversation context")
                messages = [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": "Continue playing. Call auto_pass_until_event to wait for game events."},
                ]
                calls_since_chat = 0
                consecutive_timeouts = 0

        except Exception as e:
            consecutive_timeouts = 0
            error_str = str(e)
            _log_error(game_dir, username, f"[chatterbox] LLM error: {e}")

            # Credit exhaustion - fall back to pass-only mode permanently
            if "402" in error_str:
                _log_error(game_dir, username, "[chatterbox] Credits exhausted, switching to pass-only mode")
                while True:
                    try:
                        await execute_tool(session, "auto_pass_until_event", {})
                    except Exception as pass_err:
                        _log_error(game_dir, username, f"[chatterbox] Pass-only error: {pass_err}")
                        await asyncio.sleep(5)

            # Transient error - keep actions flowing while waiting to retry
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            # Reset conversation on error
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": "Continue playing. Call auto_pass_until_event to wait for game events."},
            ]


async def run_chatterbox(
    server: str,
    port: int,
    username: str,
    project_root: Path,
    deck_path: Path | None = None,
    api_key: str = "",
    model: str = DEFAULT_MODEL,
    base_url: str = DEFAULT_BASE_URL,
    system_prompt: str = DEFAULT_SYSTEM_PROMPT,
    game_dir: Path | None = None,
    prices: dict[str, tuple[float, float]] | None = None,
) -> None:
    """Run the chatterbox client."""
    _log(f"[chatterbox] Starting for {username}@{server}:{port}")
    _log(f"[chatterbox] Model: {model}")
    _log(f"[chatterbox] Base URL: {base_url}")

    # Initialize OpenAI-compatible client
    llm_client = AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
        timeout=LLM_REQUEST_TIMEOUT_SECS + 5,
        max_retries=1,
    )

    # Build JVM args for the skeleton (same as sleepwalker)
    jvm_args_list = [
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        f"-Dxmage.headless.server={server}",
        f"-Dxmage.headless.port={port}",
        f"-Dxmage.headless.username={username}",
        "-Dxmage.headless.personality=sleepwalker",
    ]
    if sys.platform == "darwin":
        jvm_args_list.append("-Dapple.awt.UIElement=true")
    jvm_args = " ".join(jvm_args_list)

    env = os.environ.copy()
    env["MAVEN_OPTS"] = jvm_args

    # Pass deck path as a Maven CLI arg (not in MAVEN_OPTS) because
    # MAVEN_OPTS gets shell-split by the mvn script, breaking paths with spaces.
    mvn_args = ["-q"]
    if deck_path:
        mvn_args.append(f"-Dxmage.headless.deck={deck_path}")
    if game_dir:
        mvn_args.append(f"-Dxmage.headless.errorlog={game_dir / f'{username}_errors.log'}")
    mvn_args.append("exec:java")

    server_params = StdioServerParameters(
        command="mvn",
        args=mvn_args,
        cwd=str(project_root / "Mage.Client.Headless"),
        env=env,
    )

    _log("[chatterbox] Spawning skeleton client...")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            result = await session.initialize()
            _log(f"[chatterbox] MCP initialized: {result.serverInfo}")

            tools_result = await session.list_tools()
            openai_tools = mcp_tools_to_openai(tools_result.tools)
            _log(f"[chatterbox] Available tools: {[t.name for t in tools_result.tools]}")

            _log("[chatterbox] Starting LLM loop...")
            await run_llm_loop(session, llm_client, model, system_prompt, openai_tools,
                               username=username, game_dir=game_dir, prices=prices)


def main() -> int:
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Chatterbox LLM client for XMage")
    parser.add_argument("--server", default="localhost", help="XMage server address")
    parser.add_argument("--port", type=int, default=17171, help="XMage server port")
    parser.add_argument("--username", default="Chatty", help="Player username")
    parser.add_argument("--project-root", type=Path, help="Project root directory")
    parser.add_argument("--deck", type=Path, help="Path to deck file (.dck)")
    parser.add_argument("--api-key", default="", help="API key (prefer OPENROUTER_API_KEY env var)")
    parser.add_argument("--model", default=DEFAULT_MODEL, help=f"LLM model (default: {DEFAULT_MODEL})")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"API base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--system-prompt", default=DEFAULT_SYSTEM_PROMPT, help="Custom system prompt")
    parser.add_argument("--game-dir", type=Path, help="Game directory for cost file output")
    args = parser.parse_args()

    # Determine project root
    if args.project_root:
        project_root = args.project_root.resolve()
    else:
        project_root = Path.cwd().resolve()
        if project_root.name == "puppeteer" and project_root.parent.name == "src":
            project_root = project_root.parent.parent.parent
        elif project_root.name == "puppeteer":
            project_root = project_root.parent

    # API key: CLI arg > provider-specific env var based on base URL.
    required_key_env = required_api_key_env(args.base_url)
    api_key = args.api_key or os.environ.get(required_key_env, "")
    if not api_key.strip():
        _log(f"[chatterbox] ERROR: Missing API key for {args.base_url}")
        _log(f"[chatterbox] Set {required_key_env} or pass --api-key.")
        return 2

    prices = load_prices()
    _log(f"[chatterbox] Project root: {project_root}")

    try:
        asyncio.run(run_chatterbox(
            server=args.server,
            port=args.port,
            username=args.username,
            project_root=project_root,
            deck_path=args.deck,
            api_key=api_key,
            model=args.model,
            base_url=args.base_url,
            system_prompt=args.system_prompt,
            game_dir=args.game_dir,
            prices=prices,
        ))
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
