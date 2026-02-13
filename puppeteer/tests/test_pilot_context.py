"""Tests for pilot context window management: summarisation and rendering."""

import json

from puppeteer.pilot import (
    CONTEXT_RECENT_COUNT,
    CONTEXT_SUMMARY_COUNT,
    TOOL_RESULT_MAX_CHARS,
    _build_reset_message,
    _extract_last_reasoning,
    _find_tool_name,
    _render_context,
    _summarize_tool_result,
)

# ---------------------------------------------------------------------------
# _summarize_tool_result
# ---------------------------------------------------------------------------


def test_summarize_pass_priority_action_pending():
    content = json.dumps({"actions_passed": 0, "action_type": "GAME_SELECT", "action_pending": True})
    result = _summarize_tool_result("pass_priority", content)
    assert "action_pending" in result
    assert "GAME_SELECT" in result
    assert len(result) < 100


def test_summarize_pass_priority_timeout():
    content = json.dumps({"timeout": True})
    assert _summarize_tool_result("pass_priority", content) == "timeout"


def test_summarize_pass_priority_passed():
    content = json.dumps({"actions_passed": 3})
    result = _summarize_tool_result("pass_priority", content)
    assert "passed 3" in result


def test_summarize_pass_priority_player_dead():
    content = json.dumps({"player_dead": True})
    assert _summarize_tool_result("pass_priority", content) == "player_dead"


def test_summarize_choose_action_success():
    content = json.dumps({"success": True, "action_taken": "played Lightning Bolt"})
    result = _summarize_tool_result("choose_action", content)
    assert result.startswith("OK:")
    assert "Lightning Bolt" in result


def test_summarize_choose_action_failure():
    content = json.dumps({"success": False, "error": "no pending action"})
    result = _summarize_tool_result("choose_action", content)
    assert result.startswith("FAIL:")
    assert "no pending action" in result


def test_summarize_choose_action_failure_with_error_code():
    """Error code and retryable fields should not break existing summarization."""
    content = json.dumps(
        {
            "success": False,
            "error": "Index 5 out of range (call get_action_choices first)",
            "error_code": "index_out_of_range",
            "retryable": True,
        }
    )
    result = _summarize_tool_result("choose_action", content)
    assert result.startswith("FAIL:")
    assert "out of range" in result


def test_summarize_get_action_choices():
    content = json.dumps(
        {
            "action_type": "GAME_SELECT",
            "response_type": "select",
            "choices": [
                {"description": "Mountain"},
                {"description": "Lightning Bolt [Cast]"},
                {"description": "Goblin Guide [Cast]"},
            ],
        }
    )
    result = _summarize_tool_result("get_action_choices", content)
    assert "GAME_SELECT" in result
    assert "3 choices" in result
    assert len(result) <= TOOL_RESULT_MAX_CHARS


def test_summarize_get_game_state():
    content = json.dumps(
        {
            "turn": 8,
            "phase": "main1",
            "players": [
                {"name": "Alice", "life": 15, "battlefield": [{"name": "Mountain"}] * 3},
                {"name": "Bob", "life": 12, "battlefield": [{"name": "Forest"}] * 5},
            ],
        }
    )
    result = _summarize_tool_result("get_game_state", content)
    assert "T8" in result
    assert "main1" in result
    assert "Alice:15hp/3perm" in result
    assert "Bob:12hp/5perm" in result
    assert len(result) <= TOOL_RESULT_MAX_CHARS


def test_summarize_invalid_json():
    result = _summarize_tool_result("get_game_state", "not valid json at all")
    assert result == "not valid json at all"[:TOOL_RESULT_MAX_CHARS]


def test_summarize_already_small():
    content = json.dumps({"success": True})
    result = _summarize_tool_result("send_chat_message", content)
    assert result == content[:TOOL_RESULT_MAX_CHARS]


# ---------------------------------------------------------------------------
# _find_tool_name
# ---------------------------------------------------------------------------


def _make_assistant_msg(tool_calls: list[tuple[str, str]]) -> dict:
    """Helper: build an assistant message with tool_calls."""
    return {
        "role": "assistant",
        "content": "thinking...",
        "tool_calls": [
            {"id": call_id, "type": "function", "function": {"name": name, "arguments": "{}"}}
            for call_id, name in tool_calls
        ],
    }


def _make_tool_msg(call_id: str, content: str = "{}") -> dict:
    return {"role": "tool", "tool_call_id": call_id, "content": content}


def test_find_tool_name_basic():
    history = [
        _make_assistant_msg([("call_1", "pass_priority"), ("call_2", "get_action_choices")]),
        _make_tool_msg("call_1"),
        _make_tool_msg("call_2"),
    ]
    assert _find_tool_name(history, 1, "call_1") == "pass_priority"
    assert _find_tool_name(history, 2, "call_2") == "get_action_choices"


def test_find_tool_name_missing():
    history = [
        _make_assistant_msg([("call_1", "pass_priority")]),
        _make_tool_msg("call_999"),
    ]
    assert _find_tool_name(history, 1, "call_999") == ""


def test_find_tool_name_no_assistant():
    history = [
        {"role": "user", "content": "hello"},
        _make_tool_msg("call_1"),
    ]
    assert _find_tool_name(history, 1, "call_1") == ""


# ---------------------------------------------------------------------------
# _render_context
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = "You are a test pilot."
STATE_SUMMARY = "Turn 5; Alice: 20hp. "


def _make_history(n: int) -> list[dict]:
    """Build a history of n messages with alternating assistant+tool pairs."""
    history = [{"role": "user", "content": "Start the game."}]
    call_idx = 0
    while len(history) < n:
        call_id = f"call_{call_idx}"
        history.append(_make_assistant_msg([(call_id, "pass_priority")]))
        history.append(_make_tool_msg(call_id, json.dumps({"actions_passed": call_idx, "timeout": True})))
        call_idx += 1
    return history[:n]


def test_render_short_history():
    """Under threshold: all messages at full fidelity, no state bridge."""
    history = _make_history(5)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY)
    # system prompt + all 5 history entries
    assert len(messages) == 6
    assert messages[0] == {"role": "system", "content": SYSTEM_PROMPT}
    # History messages should be unchanged
    for i, msg in enumerate(history):
        assert messages[i + 1] == msg


def test_render_long_history_summarizes_old():
    """Over threshold: old tool results get summarised."""
    n = CONTEXT_RECENT_COUNT + CONTEXT_SUMMARY_COUNT + 10
    history = _make_history(n)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY)

    # Should have: system + state bridge + summarised slice + recent slice
    assert messages[0]["role"] == "system"
    assert messages[1]["role"] == "user"
    assert STATE_SUMMARY in messages[1]["content"]

    # Find tool messages in the summarised section (between bridge and recent)
    recent_start_idx = len(messages) - CONTEXT_RECENT_COUNT
    summarised_section = messages[2:recent_start_idx]
    for msg in summarised_section:
        if msg["role"] == "tool":
            # Should be summarised (short)
            assert len(msg["content"]) <= TOOL_RESULT_MAX_CHARS


def test_render_preserves_recent_full():
    """Last CONTEXT_RECENT_COUNT messages should be at full fidelity."""
    n = CONTEXT_RECENT_COUNT + CONTEXT_SUMMARY_COUNT + 10
    history = _make_history(n)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY)

    # The last CONTEXT_RECENT_COUNT messages should match history exactly
    recent_history = history[-CONTEXT_RECENT_COUNT:]
    recent_rendered = messages[-CONTEXT_RECENT_COUNT:]
    assert recent_history == recent_rendered


def test_render_includes_state_summary():
    """State bridge message should be present when history is long."""
    history = _make_history(CONTEXT_RECENT_COUNT + 5)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY)
    assert messages[1]["role"] == "user"
    assert STATE_SUMMARY in messages[1]["content"]
    assert "pass_priority" in messages[1]["content"]


def test_render_no_orphaned_tool_results():
    """Every tool message in rendered output should have its assistant pair."""
    n = CONTEXT_RECENT_COUNT + CONTEXT_SUMMARY_COUNT + 10
    history = _make_history(n)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY)

    # Check that every tool message has its tool_call_id in a preceding assistant message
    seen_call_ids: set[str] = set()
    for msg in messages:
        if msg.get("role") == "assistant":
            for tc in msg.get("tool_calls", []):
                seen_call_ids.add(tc["id"])
        elif msg.get("role") == "tool":
            assert msg["tool_call_id"] in seen_call_ids, (
                f"Orphaned tool result: {msg['tool_call_id']} not in any preceding assistant message"
            )


# ---------------------------------------------------------------------------
# save_strategy: _summarize_tool_result
# ---------------------------------------------------------------------------


def test_summarize_save_strategy():
    content = json.dumps({"saved": True, "chars": 42})
    result = _summarize_tool_result("save_strategy", content)
    assert result == "saved 42 chars"


# ---------------------------------------------------------------------------
# _extract_last_reasoning
# ---------------------------------------------------------------------------


def test_extract_last_reasoning_basic():
    history = [
        {"role": "user", "content": "Start"},
        {"role": "assistant", "content": "First thought"},
        {"role": "assistant", "content": "Second thought"},
    ]
    assert _extract_last_reasoning(history) == "Second thought"


def test_extract_last_reasoning_skips_tool_messages():
    history = [
        {"role": "assistant", "content": "My plan"},
        _make_tool_msg("call_1", "{}"),
    ]
    assert _extract_last_reasoning(history) == "My plan"


def test_extract_last_reasoning_empty_history():
    assert _extract_last_reasoning([]) == ""


def test_extract_last_reasoning_no_assistant():
    history = [{"role": "user", "content": "hello"}]
    assert _extract_last_reasoning(history) == ""


def test_extract_last_reasoning_truncates():
    history = [{"role": "assistant", "content": "x" * 500}]
    result = _extract_last_reasoning(history)
    assert len(result) == 300


def test_extract_last_reasoning_skips_none_content():
    history = [
        {"role": "assistant", "content": "Good thought"},
        {"role": "assistant", "content": None},
    ]
    assert _extract_last_reasoning(history) == "Good thought"


# ---------------------------------------------------------------------------
# _build_reset_message
# ---------------------------------------------------------------------------


def test_build_reset_message_base_only():
    result = _build_reset_message("Continue playing.", "", "")
    assert result == "Continue playing."


def test_build_reset_message_with_strategy():
    result = _build_reset_message("Continue.", "Focus on aggro", "")
    assert "Continue." in result
    assert "Your saved strategy notes: Focus on aggro" in result


def test_build_reset_message_with_reasoning():
    result = _build_reset_message("Continue.", "", "I was about to attack")
    assert "Continue." in result
    assert "Before your context was reset, you were thinking: I was about to attack" in result


def test_build_reset_message_with_both():
    result = _build_reset_message("Continue.", "Play aggro", "Attack next turn")
    assert "Continue." in result
    assert "Your saved strategy notes: Play aggro" in result
    assert "Before your context was reset, you were thinking: Attack next turn" in result
    # Strategy comes before reasoning
    assert result.index("strategy notes") < result.index("context was reset")


# ---------------------------------------------------------------------------
# _render_context with saved_strategy
# ---------------------------------------------------------------------------


def test_render_short_history_with_strategy():
    """Strategy is appended to system prompt even for short histories."""
    history = _make_history(5)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY, saved_strategy="Play aggro")
    assert messages[0]["role"] == "system"
    assert "Your saved strategy notes: Play aggro" in messages[0]["content"]
    assert messages[0]["content"].startswith(SYSTEM_PROMPT)


def test_render_short_history_no_strategy():
    """Without strategy, system prompt is unmodified."""
    history = _make_history(5)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY, saved_strategy="")
    assert messages[0] == {"role": "system", "content": SYSTEM_PROMPT}


def test_render_long_history_with_strategy():
    """Strategy is in system prompt even for long histories."""
    n = CONTEXT_RECENT_COUNT + CONTEXT_SUMMARY_COUNT + 10
    history = _make_history(n)
    messages = _render_context(history, SYSTEM_PROMPT, STATE_SUMMARY, saved_strategy="Control deck")
    assert "Your saved strategy notes: Control deck" in messages[0]["content"]
