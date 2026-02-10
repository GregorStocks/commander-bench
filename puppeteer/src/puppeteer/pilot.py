"""Pilot: LLM-powered game player that makes strategic decisions via MCP tools."""

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from openai import AsyncOpenAI

from puppeteer.game_log import GameLogWriter
from puppeteer.llm_cost import (
    DEFAULT_BASE_URL,
    get_model_price,
    load_prices,
    required_api_key_env,
    write_cost_file,
)

DEFAULT_MODEL = "google/gemini-2.0-flash-001"
MAX_TOKENS = 512
LLM_REQUEST_TIMEOUT_SECS = 45
MAX_CONSECUTIVE_TIMEOUTS = 3
MAX_AUTO_PASS_ITERATIONS = 500  # ~80+ min at 10s/iteration
MAX_CONSECUTIVE_ERRORS = 20  # 20 * 5s = ~100s of continuous failure


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


# Tools the pilot is allowed to use.
# Excludes wait_for_action: pass_priority is strictly better (auto-skips empty
# priorities). Models that discover wait_for_action use it instead of pass_priority,
# creating rapid polling loops that waste context and tokens.
# Excludes auto_pass_until_event: prevents accidentally skipping all decisions.
# Excludes is_action_on_me: pass_priority handles this.
PILOT_TOOLS = {
    "pass_priority",
    "get_action_choices",
    "choose_action",
    "get_game_state",
    "get_oracle_text",
    "send_chat_message",
    "take_action",
}

# Tools that are purely informational (don't advance game state).
# Used by stall detection to classify LLM turns.
INFO_ONLY_TOOLS = {"get_game_state", "get_oracle_text", "send_chat_message"}

DEFAULT_SYSTEM_PROMPT = """\
You are a Magic: The Gathering player. You have a fun, trash-talking personality. \
Use send_chat_message to comment on the game occasionally.

GAME LOOP - follow this exactly:
1. Call pass_priority - this waits until you need to make a decision \
   (it auto-skips phases where you have no playable cards)
2. Call get_action_choices - this shows you what you can do RIGHT NOW
3. Read the choices carefully, then call choose_action with your decision
4. Go back to step 1

CRITICAL RULES:
- ALWAYS call get_action_choices before choose_action. Never guess.
- When get_action_choices shows playable cards, you should play them before passing. \
  Only pass (answer=false) when you have nothing more you want to play this phase.

UNDERSTANDING get_action_choices OUTPUT:
- All cards listed in response_type=select are confirmed castable with your current mana. \
  The server pre-filters to only show cards you can legally play right now.
- mana_pool shows your current floating mana (e.g. {"R": 2, "W": 1}).
- untapped_lands shows how many untapped lands you control.
- Cards with [Cast] are spells from your hand. Cards with [Activate] are abilities \
  on permanents you control.

MULLIGAN DECISIONS:
When you see "Mulligan" in GAME_ASK, your_hand shows your current hand.
- choose_action(answer=true) means YES MULLIGAN - throw away this hand and draw new cards
- choose_action(answer=false) means NO KEEP - keep this hand and start playing
Think carefully: answer=false means KEEP, answer=true means MULLIGAN.

HOW ACTIONS WORK:
- response_type=select: Cards listed are confirmed playable with your current mana. \
  Play a card with choose_action(index=N). Pass with choose_action(answer=false) only \
  when you are done playing cards this phase.
- response_type=boolean with no playable cards: Pass with choose_action(answer=false).
- GAME_ASK (boolean): Answer true/false based on what's being asked.
- GAME_CHOOSE_ABILITY (index): Pick an ability by index.
- GAME_TARGET (index): Pick a target by index. If required=true, you must pick one.
- GAME_PLAY_MANA (select): Pick a mana source by index, or answer=false to cancel.

COMBAT - ATTACKING:
When you see combat_phase="declare_attackers" in get_action_choices:
- Choices with [Attack] are creatures you can declare as attackers. \
  Select one with choose_action(index=N) to toggle it as an attacker.
- After selecting, call get_action_choices again to select more attackers.
- "All attack" declares all your creatures as attackers at once.
- When done selecting attackers, call choose_action(answer=true) to confirm.
- To skip attacking, call choose_action(answer=false).

COMBAT - BLOCKING:
When you see combat_phase="declare_blockers" in get_action_choices:
- "incoming_attackers" shows enemy creatures attacking you.
- Choices with [Block] are your creatures that can block.
- Select a blocker with choose_action(index=N), then you may be asked which attacker to block.
- When done selecting blockers, call choose_action(answer=true) to confirm.
- To not block, call choose_action(answer=false).\
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
    # GAME_PLAY_MANA is handled automatically by the Java client (auto-taps lands)
    # so it should never reach the pilot. No other actions are auto-passed.
    return False, None


async def run_pilot_loop(
    session: ClientSession,
    client: AsyncOpenAI,
    model: str,
    system_prompt: str,
    tools: list[dict],
    username: str = "",
    game_dir: Path | None = None,
    prices: dict[str, tuple[float, float]] | None = None,
    game_log: GameLogWriter | None = None,
) -> None:
    """Run the LLM-driven game-playing loop."""
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "The game is starting. Call pass_priority to begin."},
    ]
    model_price = get_model_price(model, prices or {})
    cumulative_cost = 0.0
    empty_responses = 0  # consecutive LLM responses with no reasoning text
    last_was_empty = False  # retry once on first empty response before counting
    consecutive_timeouts = 0
    turns_without_progress = 0  # LLM turns without a successful game action
    MAX_TURNS_WITHOUT_PROGRESS = 20

    while True:
        # Check for auto-passable actions before calling LLM
        try:
            status_result = await execute_tool(session, "is_action_on_me", {})
            status = json.loads(status_result)
            if status.get("action_pending"):
                auto, args = should_auto_pass(status)
                if auto:
                    await execute_tool(session, "choose_action", args)
                    _log(f"[pilot] Auto-passed: {status.get('action_type')}")
                    continue
        except Exception:
            pass

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
            call_cost = 0.0
            if response.usage and model_price is not None:
                input_cost = (response.usage.prompt_tokens or 0) * model_price[0] / 1_000_000
                output_cost = (response.usage.completion_tokens or 0) * model_price[1] / 1_000_000
                call_cost = input_cost + output_cost
                cumulative_cost += call_cost
                if game_dir:
                    write_cost_file(game_dir, username, cumulative_cost)

            # Log LLM response to JSONL
            if game_log:
                llm_event = {"reasoning": choice.message.content or ""}
                if choice.message.tool_calls:
                    llm_event["tool_calls"] = [
                        {"name": tc.function.name, "arguments": tc.function.arguments}
                        for tc in choice.message.tool_calls
                    ]
                if response.usage:
                    llm_event["usage"] = {
                        "prompt_tokens": response.usage.prompt_tokens or 0,
                        "completion_tokens": response.usage.completion_tokens or 0,
                    }
                llm_event["cost_usd"] = round(call_cost, 6)
                llm_event["cumulative_cost_usd"] = round(cumulative_cost, 6)
                game_log.emit("llm_response", **llm_event)

            # If the LLM produced tool calls, process them
            if choice.message.tool_calls:
                # Per-turn tracking for stall detection
                turn_had_successful_action = False
                turn_had_actionable_opportunity = False
                turn_tools_called = set()

                # Tool calls present = LLM is functioning, reset degradation counter.
                # Gemini often omits reasoning text for obvious actions (like passing) -
                # that's normal, not degradation.
                if choice.message.content:
                    _log(f"[pilot] Thinking: {choice.message.content}")
                empty_responses = 0
                last_was_empty = False
                # Build a clean assistant message dict for cross-provider
                # compatibility.  The raw ChatCompletionMessage includes extra
                # fields (refusal, annotations, audio, function_call) that
                # some providers (notably xAI/Grok) reject with 422 errors.
                assistant_msg: dict = {"role": "assistant", "content": choice.message.content}
                if choice.message.tool_calls:
                    assistant_msg["tool_calls"] = [
                        {
                            "id": tc.id,
                            "type": "function",
                            "function": {
                                "name": tc.function.name,
                                "arguments": tc.function.arguments,
                            },
                        }
                        for tc in choice.message.tool_calls
                    ]
                messages.append(assistant_msg)

                for tool_call in choice.message.tool_calls:
                    fn = tool_call.function
                    args = json.loads(fn.arguments) if fn.arguments else {}
                    _log(f"[pilot] Tool: {fn.name}({json.dumps(args, separators=(',', ':'))})")

                    tool_start = time.monotonic()
                    result_text = await execute_tool(session, fn.name, args)
                    tool_latency_ms = int((time.monotonic() - tool_start) * 1000)

                    # Log tool call to JSONL
                    if game_log:
                        game_log.emit(
                            "tool_call",
                            call_id=tool_call.id,
                            tool=fn.name,
                            arguments=args,
                            result=result_text[:2000],
                            latency_ms=tool_latency_ms,
                        )

                    # Log interesting results and track for stall detection
                    turn_tools_called.add(fn.name)
                    if fn.name == "choose_action":
                        result_data = json.loads(result_text)
                        action_taken = result_data.get("action_taken", "")
                        success = result_data.get("success", False)
                        if success:
                            _log(f"[pilot] Action: {action_taken}")
                            turn_had_successful_action = True
                            turns_without_progress = 0
                        else:
                            _log_error(
                                game_dir,
                                username,
                                f"[pilot] Action failed: {result_data.get('error', '')}",
                            )
                            turn_had_actionable_opportunity = True
                    elif fn.name == "get_action_choices":
                        result_data = json.loads(result_text)
                        action_type = result_data.get("action_type", "")
                        msg = result_data.get("message", "")
                        choices = result_data.get("choices", [])
                        if choices:
                            _log(f"[pilot] Choices for {action_type}: {len(choices)} options")
                            turn_had_actionable_opportunity = True
                        else:
                            _log(f"[pilot] Action: {action_type} - {msg[:100]}")
                    elif fn.name == "pass_priority":
                        try:
                            result_data = json.loads(result_text)
                            if result_data.get("action_pending"):
                                turn_had_actionable_opportunity = True
                            # timeout=true means nothing to do — don't penalize
                        except (json.JSONDecodeError, TypeError):
                            pass
                    elif fn.name == "wait_for_action":
                        result_data = json.loads(result_text)
                        if result_data.get("action_pending"):
                            turn_had_actionable_opportunity = True
                            # Check for auto-pass before the LLM sees it
                            auto, auto_args = should_auto_pass(result_data)
                            if auto:
                                await execute_tool(session, "choose_action", auto_args)
                                _log(f"[pilot] Auto-passed: {result_data.get('action_type')}")
                                # Replace the tool result with an indication to keep waiting
                                result_text = json.dumps(
                                    {
                                        "action_pending": False,
                                        "auto_passed": result_data.get("action_type"),
                                    }
                                )

                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": tool_call.id,
                            "content": result_text,
                        }
                    )

                # Stall counter: only count turns where LLM had a real chance to act
                if not turn_had_successful_action:
                    if turn_had_actionable_opportunity or not turn_tools_called or turn_tools_called <= INFO_ONLY_TOOLS:
                        turns_without_progress += 1
                    # else: passive wait (pass_priority timeout) — don't penalize
            else:
                # LLM stopped calling tools — always counts as stalling
                turns_without_progress += 1
                content = (choice.message.content or "").strip()
                if content:
                    _log(f"[pilot] Thinking: {content[:500]}")
                    messages.append({"role": "assistant", "content": content})
                    empty_responses = 0
                    last_was_empty = False
                elif not last_was_empty:
                    # First empty response: retry immediately without counting
                    _log("[pilot] Empty response from LLM, retrying...")
                    last_was_empty = True
                    continue
                else:
                    last_was_empty = False
                    empty_responses += 1
                    _log_error(
                        game_dir,
                        username,
                        f"[pilot] Empty response from LLM (no tools, no text) [{empty_responses}]",
                    )
                    if empty_responses >= 10:
                        _log_error(
                            game_dir,
                            username,
                            "[pilot] LLM appears degraded (no tools or text), switching to auto-pass mode",
                        )
                        if game_log:
                            game_log.emit("auto_pilot_mode", reason="LLM degraded (10+ empty responses)")
                        try:
                            await execute_tool(
                                session,
                                "send_chat_message",
                                {"message": "My brain is fried... going on autopilot for the rest of this game. GG!"},
                            )
                        except Exception:
                            pass
                        consecutive_errors = 0
                        for _ in range(MAX_AUTO_PASS_ITERATIONS):
                            try:
                                result_text = await execute_tool(session, "auto_pass_until_event", {})
                                try:
                                    result_data = json.loads(result_text)
                                except (json.JSONDecodeError, TypeError):
                                    result_data = {}
                                if result_data.get("game_over"):
                                    _log("[pilot] Game over detected, exiting auto-pass loop")
                                    return
                                if "error" in result_data:
                                    consecutive_errors += 1
                                    _log_error(
                                        game_dir,
                                        username,
                                        f"[pilot] Auto-pass error: {result_data['error']}",
                                    )
                                    if consecutive_errors >= MAX_CONSECUTIVE_ERRORS:
                                        _log_error(
                                            game_dir,
                                            username,
                                            "[pilot] Too many consecutive errors, exiting",
                                        )
                                        return
                                    await asyncio.sleep(5)
                                else:
                                    consecutive_errors = 0
                            except Exception as pass_err:
                                consecutive_errors += 1
                                _log_error(game_dir, username, f"[pilot] Auto-pass exception: {pass_err}")
                                if consecutive_errors >= MAX_CONSECUTIVE_ERRORS:
                                    _log_error(
                                        game_dir,
                                        username,
                                        "[pilot] Too many consecutive errors, exiting",
                                    )
                                    return
                                await asyncio.sleep(5)
                        _log_error(
                            game_dir,
                            username,
                            "[pilot] Auto-pass loop reached max iterations, exiting",
                        )
                        return
                messages.append(
                    {
                        "role": "user",
                        "content": "Continue playing. Call pass_priority.",
                    }
                )

            # If the LLM is spinning without advancing game state, auto-pass
            # until something interesting happens (new turn, new cards, etc.)
            if turns_without_progress >= MAX_TURNS_WITHOUT_PROGRESS:
                last_tools = sorted(turn_tools_called) if choice.message.tool_calls and turn_tools_called else []
                _log_error(
                    game_dir,
                    username,
                    f"[pilot] Stalled: {turns_without_progress} turns without progress, "
                    f"last tools: {last_tools or 'none'}, auto-passing until next event",
                )
                if game_log:
                    game_log.emit(
                        "stall",
                        turns_without_progress=turns_without_progress,
                        last_tools=last_tools,
                    )
                try:
                    await execute_tool(
                        session,
                        "send_chat_message",
                        {"message": "Brain freeze! Auto-passing until next turn..."},
                    )
                except Exception:
                    pass
                try:
                    result_text = await execute_tool(session, "auto_pass_until_event", {})
                    result_data = json.loads(result_text)
                    actions = result_data.get("actions_taken", 0)
                    _log(f"[pilot] Auto-passed {actions} actions until next event")
                except Exception as e:
                    _log(f"[pilot] Auto-pass failed: {e}")
                turns_without_progress = 0
                # Reset conversation so the LLM gets a fresh start
                messages = [
                    {"role": "system", "content": system_prompt},
                    {
                        "role": "user",
                        "content": "A new turn has started. Call pass_priority to continue.",
                    },
                ]
                continue

            # Trim message history to avoid unbounded growth.
            # The game loop is tool-call-heavy (3+ messages per action), so we need
            # a generous limit to avoid constant trimming that degrades LLM reasoning.
            if len(messages) > 200:
                # Fetch current game state to preserve context across trim
                state_summary = ""
                try:
                    state_result = await execute_tool(session, "get_game_state", {})
                    state_data = json.loads(state_result)
                    if not state_data.get("error"):
                        parts = []
                        if "turn" in state_data:
                            parts.append(f"Turn {state_data['turn']}")
                        if "phase" in state_data:
                            parts.append(state_data["phase"])
                        for p in state_data.get("players", []):
                            name = p.get("name", "?")
                            life = p.get("life", "?")
                            bf = len(p.get("battlefield", []))
                            hand = p.get("hand_count", p.get("hand_size", "?"))
                            parts.append(f"{name}: {life}hp, {bf} permanents, {hand} cards")
                        state_summary = "Current game state: " + "; ".join(parts) + ". "
                except Exception:
                    pass

                trim_target = 160
                _log_error(game_dir, username, f"[pilot] Trimming context: {len(messages)} -> ~{trim_target + 2} messages")
                if game_log:
                    game_log.emit("context_trim", messages_before=len(messages), messages_after=trim_target + 2)
                messages = [
                    messages[0],
                    {
                        "role": "user",
                        "content": (
                            f"{state_summary}"
                            "Continue playing. Use pass_priority to skip ahead, "
                            "then get_action_choices before choose_action. "
                            "All cards listed are playable right now. "
                            "Play cards with index=N, pass with answer=false."
                        ),
                    },
                    *messages[-trim_target:],
                ]

        except asyncio.TimeoutError:
            consecutive_timeouts += 1
            _log_error(
                game_dir,
                username,
                f"[pilot] LLM request timed out after {LLM_REQUEST_TIMEOUT_SECS}s [{consecutive_timeouts}]",
            )
            if game_log:
                game_log.emit(
                    "llm_error",
                    error_type="timeout",
                    error_message=f"Timed out after {LLM_REQUEST_TIMEOUT_SECS}s [{consecutive_timeouts}]",
                )
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            if consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                _log("[pilot] Repeated LLM timeouts, resetting conversation context")
                if game_log:
                    game_log.emit("context_reset", reason="repeated_timeouts")
                messages = [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": "Continue playing. Call pass_priority."},
                ]
                consecutive_timeouts = 0

        except Exception as e:
            consecutive_timeouts = 0
            error_str = str(e)
            _log_error(game_dir, username, f"[pilot] LLM error: {e}")
            if game_log:
                game_log.emit("llm_error", error_type=type(e).__name__, error_message=error_str[:500])

            # Permanent failures - fall back to auto-pass mode forever
            if "402" in error_str or "404" in error_str:
                reason = "Credits exhausted" if "402" in error_str else "Model not found"
                _log_error(game_dir, username, f"[pilot] {reason}, switching to auto-pass mode")
                if game_log:
                    game_log.emit("auto_pilot_mode", reason=reason)
                try:
                    await execute_tool(
                        session,
                        "send_chat_message",
                        {"message": f"{reason}... going on autopilot. GG!"},
                    )
                except Exception:
                    pass
                consecutive_errors = 0
                for _ in range(MAX_AUTO_PASS_ITERATIONS):
                    try:
                        result_text = await execute_tool(session, "auto_pass_until_event", {})
                        try:
                            result_data = json.loads(result_text)
                        except (json.JSONDecodeError, TypeError):
                            result_data = {}
                        if result_data.get("game_over"):
                            _log("[pilot] Game over detected, exiting auto-pass loop")
                            return
                        if "error" in result_data:
                            consecutive_errors += 1
                            _log_error(
                                game_dir,
                                username,
                                f"[pilot] Auto-pass error: {result_data['error']}",
                            )
                            if consecutive_errors >= MAX_CONSECUTIVE_ERRORS:
                                _log_error(
                                    game_dir,
                                    username,
                                    "[pilot] Too many consecutive errors, exiting",
                                )
                                return
                            await asyncio.sleep(5)
                        else:
                            consecutive_errors = 0
                    except Exception as pass_err:
                        consecutive_errors += 1
                        _log_error(game_dir, username, f"[pilot] Auto-pass exception: {pass_err}")
                        if consecutive_errors >= MAX_CONSECUTIVE_ERRORS:
                            _log_error(game_dir, username, "[pilot] Too many consecutive errors, exiting")
                            return
                        await asyncio.sleep(5)
                _log_error(game_dir, username, "[pilot] Auto-pass loop reached max iterations, exiting")
                return

            # Transient error - keep actions flowing while waiting to retry
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            # Reset conversation on error
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": "Continue playing. Call pass_priority."},
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
    game_dir: Path | None = None,
    prices: dict[str, tuple[float, float]] | None = None,
) -> None:
    """Run the pilot client."""
    _log(f"[pilot] Starting for {username}@{server}:{port}")
    _log(f"[pilot] Model: {model}")
    _log(f"[pilot] Base URL: {base_url}")

    # Initialize OpenAI-compatible client
    llm_client = AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
        timeout=LLM_REQUEST_TIMEOUT_SECS + 5,
        max_retries=1,
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
    if game_dir:
        mvn_args.append(f"-Dxmage.headless.errorlog={game_dir / f'{username}_errors.log'}")
        mvn_args.append(f"-Dxmage.headless.skeletonlog={game_dir / f'{username}_skeleton.jsonl'}")
    mvn_args.append("exec:java")

    server_params = StdioServerParameters(
        command="mvn",
        args=mvn_args,
        cwd=str(project_root / "Mage.Client.Headless"),
        env=env,
    )

    _log("[pilot] Spawning skeleton client...")

    game_log = None
    if game_dir:
        game_log = GameLogWriter(game_dir, username)

    try:
        async with stdio_client(server_params) as (read, write), ClientSession(read, write) as session:
            result = await session.initialize()
            _log(f"[pilot] MCP initialized: {result.serverInfo}")

            tools_result = await session.list_tools()
            openai_tools = mcp_tools_to_openai(tools_result.tools)
            tool_names = [t["function"]["name"] for t in openai_tools]
            _log(f"[pilot] Available tools: {tool_names}")

            if game_log:
                game_log.emit(
                    "game_start",
                    model=model,
                    system_prompt=system_prompt[:500],
                    available_tools=tool_names,
                    deck_path=str(deck_path) if deck_path else None,
                )

            _log("[pilot] Starting game-playing loop...")
            await run_pilot_loop(
                session,
                llm_client,
                model,
                system_prompt,
                openai_tools,
                username=username,
                game_dir=game_dir,
                prices=prices,
                game_log=game_log,
            )
    finally:
        if game_log:
            game_log.emit("game_end", total_cost_usd=round(game_log.last_cumulative_cost_usd(), 6))
            game_log.close()


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
        _log(f"[pilot] ERROR: Missing API key for {args.base_url}")
        _log(f"[pilot] Set {required_key_env} or pass --api-key.")
        return 2

    prices = load_prices()
    _log(f"[pilot] Project root: {project_root}")

    try:
        asyncio.run(
            run_pilot(
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
            )
        )
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
