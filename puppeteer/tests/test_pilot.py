"""Tests for the pilot module."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from puppeteer.pilot import run_pilot_loop


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


@pytest.mark.asyncio
async def test_403_triggers_auto_pass():
    """A 403 error (key quota exceeded) should switch to auto-pass mode."""
    session = _make_session()
    client = _make_client(Exception("Error code: 403 - Forbidden"))

    with patch("puppeteer.pilot.auto_pass_loop", new_callable=AsyncMock) as mock_auto_pass:
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )
        mock_auto_pass.assert_called_once()


@pytest.mark.asyncio
async def test_402_triggers_auto_pass():
    """A 402 error (credits exhausted) should switch to auto-pass mode."""
    session = _make_session()
    client = _make_client(Exception("Error code: 402 - Payment Required"))

    with patch("puppeteer.pilot.auto_pass_loop", new_callable=AsyncMock) as mock_auto_pass:
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )
        mock_auto_pass.assert_called_once()


@pytest.mark.asyncio
async def test_404_triggers_auto_pass():
    """A 404 error (model not found) should switch to auto-pass mode."""
    session = _make_session()
    client = _make_client(Exception("Error code: 404 - Not Found"))

    with patch("puppeteer.pilot.auto_pass_loop", new_callable=AsyncMock) as mock_auto_pass:
        await run_pilot_loop(
            session=session,
            client=client,
            model="test-model",
            system_prompt="You are a test.",
            tools=[],
            username="test-player",
        )
        mock_auto_pass.assert_called_once()


@pytest.mark.asyncio
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
