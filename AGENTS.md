## Code Isolation Philosophy

Don't touch Java outside of `Mage.Client.Streaming` and `Mage.Client.Headless`. This means avoiding changes to `Mage.Client`, `Mage.Server*`, `Mage.Common`, `Mage`, `Mage.Sets`, etc. This keeps us in sync with upstream XMage.

**Our code:**
- `Mage.Client.Streaming` - streaming/observer client
- `Mage.Client.Headless` - headless client for AI harness
- `puppeteer/` - Python orchestration

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
# Start streaming observer with recording (compiles first)
make ai-harness

# Skip compilation (faster iteration)
make ai-harness-quick

# Record to specific file
make ai-harness OUTPUT=/path/to/video.mov

# Pass additional args
make ai-harness ARGS="--config myconfig.json"
```

Recordings are saved to `.context/ai-harness-logs/recording_<timestamp>.mov` by default.
