"""Tests for the pilot module."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from puppeteer.pilot import (
    PermanentLLMFailure,
    _prefetch_first_action,
    mcp_tools_to_openai,
    run_pilot_loop,
)


def _make_session() -> MagicMock:
    """Create a mock MCP session."""
    session = MagicMock()
    result = MagicMock()
    result.content = [MagicMock(text='{"ok": true}')]
    session.call_tool = AsyncMock(return_value=result)
    return session


def _make_client(error: Exception) -> MagicMock:
    """Create a mock OpenAI client whose chat.completions.create raises *error*."""
    client = MagicMock()
    client.chat.completions.create = AsyncMock(side_effect=error)
    return client


@pytest.fixture()
def _no_prefetch():
    """Patch _prefetch_first_action so run_pilot_loop tests don't block."""
    with patch("puppeteer.pilot._prefetch_first_action", new_callable=AsyncMock, return_value="Game starting."):
        yield


@pytest.mark.asyncio
@pytest.mark.usefixtures("_no_prefetch")
async def test_403_raises_permanent_failure():
    """A 403 error (key quota exceeded) should raise PermanentLLMFailure."""
    session = _make_session()
    client = _make_client(Exception("Error code: 403 - Forbidden"))

    with pytest.raises(PermanentLLMFailure, match="Credits exhausted"):
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )


@pytest.mark.asyncio
@pytest.mark.usefixtures("_no_prefetch")
async def test_402_raises_permanent_failure():
    """A 402 error (credits exhausted) should raise PermanentLLMFailure."""
    session = _make_session()
    client = _make_client(Exception("Error code: 402 - Payment Required"))

    with pytest.raises(PermanentLLMFailure, match="Credits exhausted"):
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )


@pytest.mark.asyncio
@pytest.mark.usefixtures("_no_prefetch")
async def test_404_raises_permanent_failure():
    """A 404 error (model not found) should raise PermanentLLMFailure."""
    session = _make_session()
    client = _make_client(Exception("Error code: 404 - Not Found"))

    with pytest.raises(PermanentLLMFailure, match="Model not found"):
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )


@pytest.mark.asyncio
@pytest.mark.usefixtures("_no_prefetch")
async def test_game_over_from_pass_priority_triggers_auto_pass():
    """When pass_priority returns game_over, pilot should switch to auto-pass."""
    session = _make_session()

    # Mock pass_priority to return game_over
    pass_result = MagicMock()
    pass_result.content = [MagicMock(text='{"game_over": true, "timeout": true}')]
    session.call_tool = AsyncMock(return_value=pass_result)

    # Mock LLM to call pass_priority
    tool_call = MagicMock()
    tool_call.id = "call_1"
    tool_call.function.name = "pass_priority"
    tool_call.function.arguments = '{"timeout_ms": 10000}'

    choice = MagicMock()
    choice.finish_reason = "tool_calls"
    choice.message.tool_calls = [tool_call]
    choice.message.content = None

    response = MagicMock()
    response.choices = [choice]
    response.usage = MagicMock(prompt_tokens=10, completion_tokens=5)

    client = MagicMock()
    client.chat.completions.create = AsyncMock(return_value=response)

    with patch("puppeteer.pilot.auto_pass_loop", new_callable=AsyncMock) as mock_auto_pass:
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[{"type": "function", "function": {"name": "pass_priority", "parameters": {}}}],
            username="test-player",
        )
        mock_auto_pass.assert_called_once()


# --- mcp_tools_to_openai tests ---


def _make_mcp_tool(name: str) -> MagicMock:
    """Create a mock MCP tool definition."""
    tool = MagicMock()
    tool.name = name
    tool.description = f"Description for {name}"
    tool.inputSchema = {"type": "object", "properties": {}}
    return tool


def test_mcp_tools_to_openai_no_filter():
    """With no allowed_tools, should include all MCP tools."""
    mcp_tools = [_make_mcp_tool(name) for name in ["pass_priority", "choose_action", "wait_for_action"]]
    result = mcp_tools_to_openai(mcp_tools)
    names = {t["function"]["name"] for t in result}
    assert names == {"pass_priority", "choose_action", "wait_for_action"}


def test_mcp_tools_to_openai_custom_filter():
    """With custom allowed_tools, should filter to that set."""
    mcp_tools = [_make_mcp_tool(name) for name in ["pass_priority", "choose_action", "get_game_state"]]
    custom = {"pass_priority", "get_game_state"}
    result = mcp_tools_to_openai(mcp_tools, allowed_tools=custom)
    names = {t["function"]["name"] for t in result}
    assert names == {"pass_priority", "get_game_state"}
    assert "choose_action" not in names


# --- _prefetch_first_action tests ---


def _mock_tool_result(text: str) -> MagicMock:
    result = MagicMock()
    result.content = [MagicMock(text=text)]
    return result


@pytest.mark.asyncio
async def test_prefetch_mulligan():
    """Pre-fetch should detect mulligan and build a descriptive message."""
    session = MagicMock()
    call_count = 0

    async def fake_call_tool(name, args):
        nonlocal call_count
        call_count += 1
        if name == "pass_priority":
            return _mock_tool_result('{"action_pending": true, "action_type": "GAME_ASK"}')
        if name == "get_action_choices":
            return _mock_tool_result(
                '{"action_type": "GAME_ASK", "message": "Mulligan down to 6 cards?", "choices": []}'
            )
        raise AssertionError(f"Unexpected tool: {name}")

    session.call_tool = AsyncMock(side_effect=fake_call_tool)
    msg = await _prefetch_first_action(session)
    assert "Mulligan" in msg
    assert "get_action_choices" in msg


@pytest.mark.asyncio
async def test_prefetch_waits_for_action():
    """Pre-fetch should poll pass_priority until action_pending is true."""
    session = MagicMock()
    calls = []

    async def fake_call_tool(name, args):
        calls.append(name)
        if name == "pass_priority":
            if len([c for c in calls if c == "pass_priority"]) < 3:
                return _mock_tool_result('{"action_pending": false}')
            return _mock_tool_result('{"action_pending": true, "action_type": "GAME_ASK"}')
        if name == "get_action_choices":
            return _mock_tool_result('{"action_type": "GAME_ASK", "message": "Choose play or draw"}')
        raise AssertionError(f"Unexpected tool: {name}")

    session.call_tool = AsyncMock(side_effect=fake_call_tool)
    with patch("puppeteer.pilot.asyncio.sleep", new_callable=AsyncMock):
        msg = await _prefetch_first_action(session)
    assert "GAME_ASK" in msg
    # Should have polled pass_priority multiple times
    assert calls.count("pass_priority") == 3


@pytest.mark.asyncio
async def test_prefetch_game_over():
    """If pass_priority returns game_over, return a game-over message."""
    session = MagicMock()
    session.call_tool = AsyncMock(return_value=_mock_tool_result('{"game_over": true}'))
    msg = await _prefetch_first_action(session)
    assert "over" in msg.lower()
