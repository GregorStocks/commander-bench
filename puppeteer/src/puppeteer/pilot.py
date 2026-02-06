"""Pilot: LLM-powered game player that makes strategic decisions via MCP tools."""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from openai import AsyncOpenAI


DEFAULT_MODEL = "google/gemini-2.0-flash-001"
DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
MAX_TOKENS = 512

# Tools the pilot is allowed to use (excludes auto_pass_until_event to prevent
# accidentally skipping all decisions, and excludes is_action_on_me since
# wait_for_action is strictly better).
PILOT_TOOLS = {
    "wait_for_action",
    "get_action_choices",
    "choose_action",
    "get_game_state",
    "get_oracle_text",
    "send_chat_message",
    "take_action",
}

DEFAULT_SYSTEM_PROMPT = """\
You are a skilled Magic: The Gathering player controlling a player in a Commander game.

Your game loop:
1. Call wait_for_action to wait for the game to need your input
2. Call get_action_choices to see what you can do
3. Call choose_action with your decision
4. Go back to step 1

For important decisions (casting spells, choosing targets, combat), consider calling \
get_game_state first to assess the board.

Decision guidelines:
- GAME_SELECT (response_type=boolean): true = take action, false = pass. \
  Say true when you have spells to cast, lands to play, or abilities to activate. \
  Say false to pass priority when you have nothing useful to do.
- GAME_ASK (response_type=boolean): Read the question carefully and answer yes/no.
- GAME_CHOOSE_ABILITY (response_type=index): Pick the best ability. Play lands when \
  possible, cast creatures and removal spells, activate useful abilities.
- GAME_TARGET (response_type=index): Pick the best target. Prioritize removing \
  threatening creatures and problematic permanents.
- GAME_PLAY_MANA (response_type=boolean): Almost always answer false (auto-pay).

Strategy:
- Play a land every turn when possible
- Cast creatures and spells when you have mana
- Attack when you have favorable combat
- Remove threatening permanents
- Keep mana open for responses when appropriate
- In Commander, spread damage and don't make yourself the biggest threat early

IMPORTANT: Always call get_action_choices before choose_action. \
The index values in choose_action correspond to the choices array from get_action_choices.\
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
        if tool.name in PILOT_TOOLS
    ]


async def execute_tool(session: ClientSession, name: str, arguments: dict) -> str:
    """Route a tool call through the MCP session and return the result text."""
    try:
        result = await session.call_tool(name, arguments)
        return result.content[0].text
    except Exception as e:
        return json.dumps({"error": str(e)})


def should_auto_pass(action_info: dict) -> tuple[bool, dict | None]:
    """Determine if this action can be auto-handled without the LLM.

    Returns (should_auto, choose_args) where choose_args is the
    arguments to pass to choose_action if should_auto is True.
    """
    action_type = action_info.get("action_type", "")

    # Always auto-handle mana payment
    if action_type in ("GAME_PLAY_MANA", "GAME_PLAY_XMANA"):
        return True, {"answer": False}

    return False, None


async def run_pilot_loop(
    session: ClientSession,
    client: AsyncOpenAI,
    model: str,
    system_prompt: str,
    tools: list[dict],
) -> None:
    """Run the LLM-driven game-playing loop."""
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "The game is starting. Call wait_for_action to begin."},
    ]

    while True:
        # Check for auto-passable actions before calling LLM
        try:
            status_result = await execute_tool(session, "is_action_on_me", {})
            status = json.loads(status_result)
            if status.get("action_pending"):
                auto, args = should_auto_pass(status)
                if auto:
                    await execute_tool(session, "choose_action", args)
                    print(f"[pilot] Auto-passed: {status.get('action_type')}")
                    continue
        except Exception:
            pass

        try:
            response = await client.chat.completions.create(
                model=model,
                messages=messages,
                tools=tools,
                tool_choice="auto",
                max_tokens=MAX_TOKENS,
            )
            choice = response.choices[0]

            # If the LLM produced tool calls, process them
            if choice.message.tool_calls:
                # Show LLM reasoning alongside tool calls
                if choice.message.content:
                    print(f"[pilot] Thinking: {choice.message.content}")
                messages.append(choice.message)

                for tool_call in choice.message.tool_calls:
                    fn = tool_call.function
                    args = json.loads(fn.arguments) if fn.arguments else {}
                    print(f"[pilot] Tool: {fn.name}({json.dumps(args, separators=(',', ':'))})")

                    result_text = await execute_tool(session, fn.name, args)

                    # Log interesting results
                    if fn.name == "choose_action":
                        result_data = json.loads(result_text)
                        action_taken = result_data.get("action_taken", "")
                        success = result_data.get("success", False)
                        if success:
                            print(f"[pilot] Action: {action_taken}")
                        else:
                            print(f"[pilot] Action failed: {result_data.get('error', '')}")
                    elif fn.name == "get_action_choices":
                        result_data = json.loads(result_text)
                        action_type = result_data.get("action_type", "")
                        msg = result_data.get("message", "")
                        choices = result_data.get("choices", [])
                        if choices:
                            print(f"[pilot] Choices for {action_type}: {len(choices)} options")
                        else:
                            print(f"[pilot] Action: {action_type} - {msg[:100]}")
                    elif fn.name == "wait_for_action":
                        result_data = json.loads(result_text)
                        if result_data.get("action_pending"):
                            # Check for auto-pass before the LLM sees it
                            auto, auto_args = should_auto_pass(result_data)
                            if auto:
                                await execute_tool(session, "choose_action", auto_args)
                                print(f"[pilot] Auto-passed: {result_data.get('action_type')}")
                                # Replace the tool result with an indication to keep waiting
                                result_text = json.dumps({
                                    "action_pending": False,
                                    "auto_passed": result_data.get("action_type"),
                                })

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": result_text,
                    })
            else:
                # LLM stopped calling tools - prompt it to continue
                content = (choice.message.content or "").strip()
                if content:
                    print(f"[pilot] Thinking: {content[:500]}")
                    messages.append({"role": "assistant", "content": content})
                messages.append({
                    "role": "user",
                    "content": "Continue playing. Call wait_for_action.",
                })

            # Trim message history to avoid unbounded growth
            if len(messages) > 40:
                messages = (
                    [messages[0]]
                    + [{"role": "user", "content": "Continue playing. Make strategic decisions. Always call get_action_choices before choose_action."}]
                    + messages[-25:]
                )

        except Exception as e:
            error_str = str(e)
            print(f"[pilot] LLM error: {e}")

            # Credit exhaustion - fall back to auto-pass mode permanently
            if "402" in error_str:
                print("[pilot] Credits exhausted, switching to auto-pass mode")
                while True:
                    try:
                        await execute_tool(session, "auto_pass_until_event", {})
                    except Exception as pass_err:
                        print(f"[pilot] Pass-only error: {pass_err}")
                        await asyncio.sleep(5)

            # Transient error - keep actions flowing while waiting to retry
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            # Reset conversation on error
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": "Continue playing. Call wait_for_action."},
            ]


async def run_pilot(
    server: str,
    port: int,
    username: str,
    project_root: Path,
    deck_path: Path | None = None,
    api_key: str = "",
    model: str = DEFAULT_MODEL,
    base_url: str = DEFAULT_BASE_URL,
    system_prompt: str = DEFAULT_SYSTEM_PROMPT,
) -> None:
    """Run the pilot client."""
    print(f"[pilot] Starting for {username}@{server}:{port}")
    print(f"[pilot] Model: {model}")
    print(f"[pilot] Base URL: {base_url}")

    if not api_key:
        print("[pilot] ERROR: No API key provided. Set OPENROUTER_API_KEY environment variable.")
        return

    # Initialize OpenAI-compatible client
    llm_client = AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
    )

    # Build JVM args for the skeleton (same as sleepwalker/chatterbox)
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
    # Maven CLI -D args go through "$@" which preserves spaces correctly.
    mvn_args = ["-q"]
    if deck_path:
        mvn_args.append(f"-Dxmage.headless.deck={deck_path}")
    mvn_args.append("exec:java")

    server_params = StdioServerParameters(
        command="mvn",
        args=mvn_args,
        cwd=str(project_root / "Mage.Client.Headless"),
        env=env,
    )

    print("[pilot] Spawning skeleton client...")

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            result = await session.initialize()
            print(f"[pilot] MCP initialized: {result.serverInfo}")

            tools_result = await session.list_tools()
            openai_tools = mcp_tools_to_openai(tools_result.tools)
            print(f"[pilot] Available tools: {[t['function']['name'] for t in openai_tools]}")

            print("[pilot] Starting game-playing loop...")
            await run_pilot_loop(session, llm_client, model, system_prompt, openai_tools)


def main() -> int:
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Pilot LLM game player for XMage")
    parser.add_argument("--server", default="localhost", help="XMage server address")
    parser.add_argument("--port", type=int, default=17171, help="XMage server port")
    parser.add_argument("--username", default="Pilot", help="Player username")
    parser.add_argument("--project-root", type=Path, help="Project root directory")
    parser.add_argument("--deck", type=Path, help="Path to deck file (.dck)")
    parser.add_argument("--api-key", default="", help="API key (prefer OPENROUTER_API_KEY env var)")
    parser.add_argument("--model", default=DEFAULT_MODEL, help=f"LLM model (default: {DEFAULT_MODEL})")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"API base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--system-prompt", default=DEFAULT_SYSTEM_PROMPT, help="Custom system prompt")
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

    # API key: CLI arg > env var
    api_key = args.api_key or os.environ.get("OPENROUTER_API_KEY", "")

    print(f"[pilot] Project root: {project_root}")

    try:
        asyncio.run(run_pilot(
            server=args.server,
            port=args.port,
            username=args.username,
            project_root=project_root,
            deck_path=args.deck,
            api_key=api_key,
            model=args.model,
            base_url=args.base_url,
            system_prompt=args.system_prompt,
        ))
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
