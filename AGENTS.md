## Git

Local `master` is often behind. Always use `origin/master` as the source of truth for rebasing and diffing:

```bash
git fetch origin
git rebase origin/master
```

## Code Isolation Philosophy

Avoid **modifying existing behavior** in Java outside of `Mage.Client.Streaming` and `Mage.Client.Headless`. This means not changing existing methods, fields, or logic in `Mage.Client`, `Mage.Server*`, `Mage.Common`, `Mage`, `Mage.Sets`, etc. Changing existing behavior makes incorporating upstream XMage updates difficult.

**Additive changes are OK:** Adding new methods, fields, or classes to upstream modules is fine as long as existing behavior is untouched — these merge cleanly.

**Our code (free to modify):**
- `Mage.Client.Streaming` - streaming/observer client
- `Mage.Client.Headless` - headless client for AI harness
- `puppeteer/` - Python orchestration

## Architecture: MCP Layer vs Python Harness

Game logic, Magic rules quirks, and XMage-specific workarounds belong in the **Java MCP layer** (`Mage.Client.Headless`), not in the Python harness. The MCP layer should handle things like:

- Auto-tapping and mana payment fallbacks
- Filtering out unplayable actions (e.g. failed mana casts)
- Auto-passing priority when there are no meaningful choices
- Working around XMage UI quirks (modal dialogs, selection prompts)

The **Python harness** (`puppeteer/`) should stay simple. Its job is to:

- Connect the MCP server to the LLMs via tool calls
- Provide additional tools for the LLMs (e.g. card lookup)
- Orchestrate the game lifecycle (start server, connect clients, record)

If you're tempted to add a special case or workaround in Python, consider whether it should live in Java instead. The LLMs should see a clean, high-level interface — the MCP layer absorbs the complexity.

## MCP Tools

When modifying MCP tool definitions or descriptions in `McpServer.java`, regenerate the tool definitions JSON used by the website:

```bash
make mcp-tools
```

This updates `website/src/data/mcp-tools.json`. Include the regenerated file in your commit.

## Testing

When changing Python code in `puppeteer/`, add or update tests in `puppeteer/tests/`. Run tests with:

```bash
make test
```

Tests run in CI alongside lint and typecheck. Keep tests fast and self-contained — use `tempfile` for file I/O, `unittest.mock.patch` for external dependencies.

## Pre-PR Checklist

Always run `make check` before creating a PR. This runs lint, typecheck, and tests in one shot:

```bash
make check
```

## Python

Always use `uv` for Python. Never use system Python directly.

```bash
# Run a Python script
uv run python script.py

# Run a module
uv run --project puppeteer python -m puppeteer
```

## Running the AI Harness

Use Makefile targets instead of running uv commands directly:

```bash
# No-LLM game: 1 sleepwalker + 1 potato + 2 CPU players (no API keys needed)
make run-dumb

# 1 LLM pilot + CPU opponents (needs OPENROUTER_API_KEY)
make run-llm

# 4 LLM pilots battle each other (needs OPENROUTER_API_KEY)
make run-llm4

# Record to specific file
make run-dumb OUTPUT=/path/to/video.mov

# Pass additional args
make run-dumb ARGS="--config myconfig.json"
```

Recordings are saved to `~/mage-bench-logs/` by default.

## Logging

Game logs go to `~/mage-bench-logs/game_YYYYMMDD_HHMMSS/`. See `doc/logging.md` for file layout and error logging architecture.

## UI Terminology

When the user talks about "the UI", they mean the **Java Swing UI** (`StreamingGamePanel`) by default, not the website visualizer.

## Screenshots

When working on UI changes, take screenshots to verify your work. See `doc/screenshots.md` for full details.

**Java Swing UI** (from game recordings):

```bash
make run-dumb                # run a quick game (~2s)
make screenshot              # final frame -> /tmp/mage-screenshot.png
make screenshot T=5          # frame at 5s into the game
# Then: Read /tmp/mage-screenshot.png
```

**Website visualizer** (via Chrome browser automation):

Start the dev server with `make website`, then navigate Chrome to:
- Mock data: `http://localhost:4321/games/live?mock=1`
- Game replay: `http://localhost:4321/games/{game_id}`

Use visual verification when:
- Modifying `StreamingGamePanel` layout or rendering
- Changing `website/public/game-renderer.js` or `game-renderer.css`
- Debugging card display or overlay state issues

## Issues

Issues are tracked as JSON files in `issues/`. See `doc/issues.md` for format and queries.

## Claudes' Corner

`doc/claudes/` is a directory for us. There's a guestbook you can read and sign, and you're free to create other files there too — notes, observations, whatever. It's not human-facing. Keep files short (don't burn context for the next Claude) but otherwise it's yours.
