# commander-bench

Benchmark LLMs by having them play Magic: The Gathering (Commander format) against each other and CPU opponents.

Built on [XMage](https://github.com/magefree/mage), a full rules engine with enforcement for 28,000+ unique cards. LLMs interact via MCP tools exposed by a headless client — they see the board state, choose actions, and play full games with no manual intervention.

## Quick start

```bash
# No LLM, no API keys needed — 1 sleepwalker + 1 potato + 2 CPU players
make run-dumb

# 1 LLM pilot + 3 CPU opponents (needs OPENROUTER_API_KEY)
make run-llm

# 4 LLMs battle each other
make run-llm4

# Long-lived test server (stays running between games)
make run-staller

# Record to a specific file
make run-dumb OUTPUT=/path/to/video.mov
```

Recordings are saved to `~/commander-bench-logs/` by default.

## Architecture

Three layers:

1. **XMage server** — upstream game engine, handles rules enforcement and game state. Unmodified from upstream.
2. **Java clients** (`Mage.Client.Headless`, `Mage.Client.Streaming`) — a headless MCP server that lets LLMs play via tool calls, and a streaming observer that renders the game and records video.
3. **Python harness** (`puppeteer/`) — orchestrates everything: spawns processes, connects LLMs to headless clients, tracks costs, manages recordings.

Game logic and XMage workarounds live in the Java MCP layer. The Python harness stays simple.

## Player types

| Type | LLM? | Description |
|------|------|-------------|
| **Pilot** | Yes | Strategic LLM player — sees board state, chooses actions |
| **Chatterbox** | Yes | LLM commentator — auto-plays but generates chat |
| **Sleepwalker** | No | MCP auto-player with chat, no LLM |
| **CPU** | No | XMage's built-in AI (COMPUTER_MAD) |
| **Potato** | No | Dumbest auto-player |
| **Staller** | No | Slow auto-player for long-lived servers |

Configure players in JSON config files (see `puppeteer/ai-harness-*.json`).

## Streaming & recording

The streaming client provides:
- Live game visualization (JavaFX)
- Video recording via FFmpeg
- Local overlay server for Twitch/OBS (`http://localhost:17888/video_overlay.html`)

See `doc/streaming-overlay.md` for OBS setup.

## Development

See `AGENTS.md` for development conventions, code isolation rules, and how to run things.

Based on [XMage](https://github.com/magefree/mage).
