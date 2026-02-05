## Code Isolation Philosophy

Minimize changes to baseline XMage code to enable clean rebasing against upstream.

**Baseline (avoid modifying):** `Mage.Client`, `Mage.Server*`, `Mage.Common`, `Mage`, `Mage.Sets`

**Our code (where changes should live):**
- `Mage.Client.Streaming` - streaming/observer client
- `Mage.Client.Headless` - headless client for AI harness
- `puppeteer/` - Python orchestration

See [doc/architecture.md](doc/architecture.md) for details on acceptable baseline modifications and current audit.

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
# Start streaming observer (compiles first)
make ai-harness

# Start with video recording
make ai-harness-record

# Record to specific file
make ai-harness-record-to OUTPUT=/path/to/video.mov

# Skip compilation (faster iteration)
make ai-harness-skip-compile

# Record with skip compilation
make ai-harness-record-skip-compile
```

Recordings are saved to `.context/ai-harness-logs/recording_<timestamp>.mov` by default.
