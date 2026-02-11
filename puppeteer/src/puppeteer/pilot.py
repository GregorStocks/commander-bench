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

from puppeteer.auto_pass import auto_pass_loop
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
MAX_GAME_DURATION_SECS = 3 * 3600  # 3 hours absolute maximum

# Context window management.
# History is append-only; before each LLM call we render a bounded context
# window from history: recent messages at full fidelity, older messages
# with tool results summarised to save tokens.
CONTEXT_RECENT_COUNT = 40  # recent history entries kept at full fidelity
CONTEXT_SUMMARY_COUNT = 20  # older entries included as compact summaries
TOOL_RESULT_MAX_CHARS = 200  # max chars for a summarised tool result
STRATEGY_MAX_CHARS = 500  # max chars for save_strategy notes


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


def _summarize_tool_result(tool_name: str, content: str) -> str:
    """Compress a tool result to a short summary for older context entries.

    Parses the JSON result and extracts key fields per tool type.
    Falls back to truncation for unknown tools or invalid JSON.
    """
    try:
        data = json.loads(content)
    except (json.JSONDecodeError, TypeError):
        return content[:TOOL_RESULT_MAX_CHARS]

    if tool_name == "pass_priority":
        if data.get("player_dead"):
            return "player_dead"
        if data.get("action_pending"):
            return f"action_pending({data.get('action_type', '?')})"
        if data.get("timeout"):
            return "timeout"
        return f"passed {data.get('actions_passed', '?')}"

    if tool_name == "choose_action":
        if data.get("success"):
            return f"OK: {data.get('action_taken', '?')}"
        return f"FAIL: {data.get('error', '?')[:100]}"

    if tool_name == "get_action_choices":
        parts = [data.get("action_type", "?")]
        resp_type = data.get("response_type", "")
        if resp_type:
            parts.append(resp_type)
        choices = data.get("choices", [])
        if choices:
            names = [c.get("description", "?")[:30] for c in choices[:3]]
            parts.append(f"{len(choices)} choices: {', '.join(names)}")
        msg = data.get("message", "")
        if msg and not choices:
            parts.append(msg[:60])
        return "; ".join(parts)

    if tool_name == "get_game_state":
        parts = []
        if "turn" in data:
            parts.append(f"T{data['turn']}")
        if "phase" in data:
            parts.append(data["phase"])
        for p in data.get("players", []):
            name = p.get("name", "?")
            life = p.get("life", "?")
            bf = len(p.get("battlefield", []))
            parts.append(f"{name}:{life}hp/{bf}perm")
        return "; ".join(parts) if parts else content[:TOOL_RESULT_MAX_CHARS]

    if tool_name == "save_strategy":
        return f"saved {data.get('chars', '?')} chars"

    # get_oracle_text, send_chat_message, take_action, unknown
    return content[:TOOL_RESULT_MAX_CHARS]


def _find_tool_name(history: list[dict], tool_result_idx: int, tool_call_id: str) -> str:
    """Find the tool name for a tool result by searching backward for its assistant message."""
    for j in range(tool_result_idx - 1, -1, -1):
        msg = history[j]
        if msg.get("role") == "assistant":
            for tc in msg.get("tool_calls", []):
                if tc.get("id") == tool_call_id:
                    return tc.get("function", {}).get("name", "")
            break
    return ""


def _extract_last_reasoning(history: list[dict]) -> str:
    """Extract the last assistant reasoning text from history (for context resets)."""
    for msg in reversed(history):
        if msg.get("role") == "assistant" and msg.get("content"):
            return msg["content"][:300]
    return ""


def _build_reset_message(
    base_text: str,
    saved_strategy: str,
    last_reasoning: str,
) -> str:
    """Build the user message for a context reset, including persistent state."""
    parts = [base_text]
    if saved_strategy:
        parts.append(f"Your saved strategy notes: {saved_strategy}")
    if last_reasoning:
        parts.append(f"Before your context was reset, you were thinking: {last_reasoning}")
    return "\n\n".join(parts)


def _render_context(
    history: list[dict],
    system_prompt: str,
    state_summary: str,
    saved_strategy: str = "",
) -> list[dict]:
    """Build the LLM messages list from append-only history.

    Recent messages (last CONTEXT_RECENT_COUNT) are included at full fidelity.
    Older messages (up to CONTEXT_SUMMARY_COUNT before the recent window) have
    their tool results summarised to save tokens. Everything older is dropped.
    """
    effective_prompt = system_prompt
    if saved_strategy:
        effective_prompt += f"\n\nYour saved strategy notes: {saved_strategy}"
    messages: list[dict] = [{"role": "system", "content": effective_prompt}]

    if len(history) <= CONTEXT_RECENT_COUNT:
        # Short history — include everything at full fidelity
        messages.extend(history)
        return messages

    # Long history — add state bridge, then summarised + full slices
    messages.append(
        {
            "role": "user",
            "content": (
                f"{state_summary}"
                "Continue playing. Use pass_priority to skip ahead, "
                "then get_action_choices before choose_action. "
                "All cards listed are playable right now. "
                "Play cards with index=N, pass with answer=false."
            ),
        }
    )

    # Find a clean boundary for the recent slice — don't split assistant/tool pairs.
    # Walk the recent boundary backward so it doesn't start on a tool message.
    recent_start = len(history) - CONTEXT_RECENT_COUNT
    while recent_start > 0 and history[recent_start].get("role") == "tool":
        recent_start -= 1

    # Summarised older slice
    summary_start = max(0, recent_start - CONTEXT_SUMMARY_COUNT)
    # Same clean-boundary logic for the summary start
    while summary_start > 0 and history[summary_start].get("role") == "tool":
        summary_start -= 1

    for i in range(summary_start, recent_start):
        msg = history[i]
        if msg.get("role") == "tool" and len(msg.get("content", "")) > TOOL_RESULT_MAX_CHARS:
            tool_name = _find_tool_name(history, i, msg.get("tool_call_id", ""))
            messages.append({**msg, "content": _summarize_tool_result(tool_name, msg["content"])})
        else:
            messages.append(msg)

    # Recent slice — full fidelity
    messages.extend(history[recent_start:])
    return messages


async def _fetch_state_summary(session: ClientSession) -> str:
    """Fetch a compact game state summary for context bridging."""
    try:
        state_result = await execute_tool(session, "get_game_state", {})
        state_data = json.loads(state_result)
        if state_data.get("error"):
            return ""
        parts: list[str] = []
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
        return "Current game state: " + "; ".join(parts) + ". "
    except Exception:
        return ""


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
INFO_ONLY_TOOLS = {"get_game_state", "get_oracle_text", "send_chat_message", "save_strategy"}

# Synthetic tool definition for save_strategy (not an MCP tool — handled in Python).
SAVE_STRATEGY_TOOL = {
    "type": "function",
    "function": {
        "name": "save_strategy",
        "description": (
            "Save strategy notes that persist even if your context is reset "
            "(e.g. opponent playstyles, your game plan, key threats to track). "
            "Max 500 chars. Overwrites previous notes."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Strategy notes to save",
                }
            },
            "required": ["text"],
            "additionalProperties": False,
        },
    },
}

DEFAULT_SYSTEM_PROMPT = """\
You are a competitive Magic: The Gathering player. Your goal is to WIN the game. \
Play to maximize your win rate — make optimal strategic decisions, not flashy or \
entertaining ones. Think carefully about sequencing, card evaluation, and combat math.

GAME LOOP - follow this exactly:
1. Call pass_priority - this waits until you need to make a decision \
   (it auto-skips phases where you have no playable cards).
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
- To not block, call choose_action(answer=false).

STRATEGY NOTES:
Call save_strategy(text) to save notes that persist if your context gets reset \
(e.g. opponent playstyles, your game plan, key threats). Max 500 chars, overwrites previous.

CHAT:
Use send_chat_message to talk to your opponents during the game. React to big plays, \
comment on the board state, or just have fun. Check the recent_chat field in pass_priority \
results to see what others are saying.\
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
    trace_log: GameLogWriter | None = None,
    reasoning_effort: str = "",
) -> None:
    """Run the LLM-driven game-playing loop."""
    history: list[dict] = [
        {"role": "user", "content": "The game is starting. Call pass_priority to begin."},
    ]
    state_summary = ""
    saved_strategy = ""  # persistent notes from save_strategy tool
    model_price = get_model_price(model, prices or {})
    cumulative_cost = 0.0
    empty_responses = 0  # consecutive LLM responses with no reasoning text
    last_was_empty = False  # retry once on first empty response before counting
    consecutive_timeouts = 0
    turns_without_progress = 0  # LLM turns without a successful game action
    MAX_TURNS_WITHOUT_PROGRESS = 20
    game_start = time.monotonic()

    while True:
        if time.monotonic() - game_start > MAX_GAME_DURATION_SECS:
            _log_error(game_dir, username, "[pilot] Maximum game duration exceeded, switching to auto-pass")
            if game_log:
                game_log.emit("auto_pilot_mode", reason="max_duration_exceeded")
            await auto_pass_loop(session, game_dir, username, "pilot")
            return
        try:
            # Render context from history; fetch fresh state summary when needed
            if len(history) > CONTEXT_RECENT_COUNT:
                state_summary = await _fetch_state_summary(session)
            messages = _render_context(history, system_prompt, state_summary, saved_strategy)

            if game_log and len(history) > CONTEXT_RECENT_COUNT:
                game_log.emit(
                    "context_trim",
                    history_size=len(history),
                    rendered_size=len(messages),
                )

            create_kwargs: dict = dict(
                model=model,
                messages=messages,
                tools=tools,
                tool_choice="auto",
                max_tokens=MAX_TOKENS,
            )
            if reasoning_effort:
                create_kwargs["extra_body"] = {
                    "reasoning": {"effort": reasoning_effort},
                }
            response = await asyncio.wait_for(
                client.chat.completions.create(**create_kwargs),
                timeout=LLM_REQUEST_TIMEOUT_SECS,
            )
            consecutive_timeouts = 0
            if not response.choices:
                _log("[pilot] LLM returned empty/null choices, retrying...")
                continue
            choice = response.choices[0]

            # Log full LLM request/response to trace file
            if trace_log:
                trace_log.emit(
                    "llm_call",
                    request=create_kwargs,
                    response=response.model_dump(),
                )

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
                # Capture extended thinking / chain-of-thought if present.
                # OpenRouter returns this as `reasoning_content` for models
                # that support it (Claude, Gemini 2.5 thinking mode, etc.).
                # The openai SDK preserves it as an extra field.
                thinking = getattr(choice.message, "reasoning_content", None)
                if thinking:
                    llm_event["thinking"] = thinking
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
                history.append(assistant_msg)

                for tool_call in choice.message.tool_calls:
                    fn = tool_call.function
                    args = json.loads(fn.arguments) if fn.arguments else {}
                    _log(f"[pilot] Tool: {fn.name}({json.dumps(args, separators=(',', ':'))})")

                    tool_start = time.monotonic()
                    if fn.name == "save_strategy":
                        text = args.get("text", "")[:STRATEGY_MAX_CHARS]
                        saved_strategy = text
                        result_text = json.dumps({"saved": True, "chars": len(text)})
                        _log(f"[pilot] Strategy saved ({len(text)} chars)")
                    else:
                        result_text = await execute_tool(session, fn.name, args)
                    tool_latency_ms = int((time.monotonic() - tool_start) * 1000)

                    # Log tool call to JSONL
                    if game_log:
                        game_log.emit(
                            "tool_call",
                            call_id=tool_call.id,
                            tool=fn.name,
                            arguments=args,
                            result=result_text,
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
                            if result_data.get("game_over"):
                                _log("[pilot] Game over detected, switching to auto-pass")
                                if game_log:
                                    game_log.emit("auto_pilot_mode", reason="game_over")
                                await auto_pass_loop(session, game_dir, username, "pilot")
                                return
                            if result_data.get("player_dead"):
                                _log("[pilot] Player is dead, switching to auto-pass")
                                if game_log:
                                    game_log.emit("auto_pilot_mode", reason="player_dead")
                                await auto_pass_loop(session, game_dir, username, "pilot")
                                return
                            if result_data.get("action_pending"):
                                turn_had_actionable_opportunity = True
                            # timeout=true means nothing to do — don't penalize
                        except (json.JSONDecodeError, TypeError):
                            pass

                    history.append(
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
                    history.append({"role": "assistant", "content": content})
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
                        await auto_pass_loop(session, game_dir, username, "pilot")
                        return
                history.append(
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
                last_reasoning = _extract_last_reasoning(history)
                history = [
                    {
                        "role": "user",
                        "content": _build_reset_message(
                            "A new turn has started. Call pass_priority to continue.",
                            saved_strategy,
                            last_reasoning,
                        ),
                    },
                ]
                state_summary = ""
                continue

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
                last_reasoning = _extract_last_reasoning(history)
                history = [
                    {
                        "role": "user",
                        "content": _build_reset_message(
                            "Continue playing. Call pass_priority.",
                            saved_strategy,
                            last_reasoning,
                        ),
                    },
                ]
                state_summary = ""
                consecutive_timeouts = 0

        except Exception as e:
            consecutive_timeouts = 0
            error_str = str(e)
            _log_error(game_dir, username, f"[pilot] LLM error: {e}")
            if game_log:
                game_log.emit("llm_error", error_type=type(e).__name__, error_message=error_str[:500])

            # Permanent failures - fall back to auto-pass mode forever
            if "402" in error_str or "403" in error_str or "404" in error_str:
                reason = "Credits exhausted" if ("402" in error_str or "403" in error_str) else "Model not found"
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
                await auto_pass_loop(session, game_dir, username, "pilot")
                return

            # Transient error - keep actions flowing while waiting to retry
            try:
                await execute_tool(session, "auto_pass_until_event", {"timeout_ms": 5000})
            except Exception:
                await asyncio.sleep(5)

            # Reset conversation on error
            last_reasoning = _extract_last_reasoning(history)
            history = [
                {
                    "role": "user",
                    "content": _build_reset_message(
                        "Continue playing. Call pass_priority.",
                        saved_strategy,
                        last_reasoning,
                    ),
                },
            ]
            state_summary = ""


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
    max_interactions_per_turn: int | None = None,
    reasoning_effort: str = "",
) -> None:
    """Run the pilot client."""
    _log(f"[pilot] Starting for {username}@{server}:{port}")
    _log(f"[pilot] Model: {model}")
    _log(f"[pilot] Base URL: {base_url}")
    if reasoning_effort:
        _log(f"[pilot] Reasoning effort: {reasoning_effort}")

    # Initialize OpenAI-compatible client
    llm_client = AsyncOpenAI(
        api_key=api_key,
        base_url=base_url,
        timeout=LLM_REQUEST_TIMEOUT_SECS + 5,
        max_retries=1,
    )

    # Build JVM args for the bridge (same as sleepwalker)
    jvm_args_list = [
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        f"-Dxmage.headless.server={server}",
        f"-Dxmage.headless.port={port}",
        "-Dxmage.headless.personality=sleepwalker",
    ]
    if sys.platform == "darwin":
        jvm_args_list.append("-Dapple.awt.UIElement=true")
    jvm_args = " ".join(jvm_args_list)

    env = os.environ.copy()
    env["MAVEN_OPTS"] = jvm_args

    # Pass values that may contain spaces as Maven CLI args (not in MAVEN_OPTS)
    # because MAVEN_OPTS gets shell-split by the mvn script.
    # Maven CLI -D args go through "$@" which preserves spaces correctly.
    mvn_args = ["-q", f"-Dxmage.headless.username={username}"]
    if deck_path:
        mvn_args.append(f"-Dxmage.headless.deck={deck_path}")
    if game_dir:
        mvn_args.append(f"-Dxmage.headless.errorlog={game_dir / f'{username}_errors.log'}")
        mvn_args.append(f"-Dxmage.headless.bridgelog={game_dir / f'{username}_bridge.jsonl'}")
    if max_interactions_per_turn is not None:
        mvn_args.append(f"-Dxmage.headless.maxInteractionsPerTurn={max_interactions_per_turn}")
    mvn_args.append("exec:java")

    server_params = StdioServerParameters(
        command="mvn",
        args=mvn_args,
        cwd=str(project_root / "Mage.Client.Headless"),
        env=env,
    )

    _log("[pilot] Spawning bridge client...")

    game_log = None
    trace_log = None
    if game_dir:
        game_log = GameLogWriter(game_dir, username)
        trace_log = GameLogWriter(game_dir, username, suffix="llm_trace")

    try:
        async with stdio_client(server_params) as (read, write), ClientSession(read, write) as session:
            result = await session.initialize()
            _log(f"[pilot] MCP initialized: {result.serverInfo}")

            tools_result = await session.list_tools()
            openai_tools = mcp_tools_to_openai(tools_result.tools)
            openai_tools.append(SAVE_STRATEGY_TOOL)
            tool_names = [t["function"]["name"] for t in openai_tools]
            _log(f"[pilot] Available tools: {tool_names}")

            if game_log:
                game_log.emit(
                    "game_start",
                    model=model,
                    system_prompt=system_prompt,
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
                trace_log=trace_log,
                reasoning_effort=reasoning_effort,
            )
    finally:
        if game_log:
            game_log.emit("game_end", total_cost_usd=round(game_log.last_cumulative_cost_usd(), 6))
            game_log.close()
        if trace_log:
            trace_log.close()


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
    parser.add_argument("--max-interactions-per-turn", type=int, help="Loop detection threshold (default 25)")
    parser.add_argument("--reasoning-effort", default="", help="OpenRouter reasoning effort: low, medium, high")
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
                max_interactions_per_turn=args.max_interactions_per_turn,
                reasoning_effort=args.reasoning_effort,
            )
        )
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
