## Code Isolation Philosophy

### Goal
Minimize changes to baseline XMage code to enable clean rebasing against upstream.

### What's Baseline (avoid modifying)
- `Mage.Client` - the normal client that real players use
- `Mage.Server*` - all server modules
- `Mage.Common` - shared types
- `Mage` - core game engine
- `Mage.Sets` - card implementations

### What's Ours (where changes should live)
- `Mage.Client.Streaming` - streaming/observer client (subclasses Mage.Client)
- `Mage.Client.Headless` - headless client for AI harness (implements interfaces)
- `puppeteer/` - Python orchestration and MCP clients

### Acceptable Baseline Modifications
- **Removing code/coupling** - simplifies rebasing
- **Extension points** - making classes non-final, adding protected accessors, factory methods to enable subclassing
- **Dependency version bumps** - for compatibility (e.g., Apple Silicon)

### Discouraged Baseline Modifications
- Adding new fields/methods to UI components
- Adding new features to baseline modules
- Changing baseline behavior

### Current Baseline Modifications (Audit)

**Server (Mage.Server, Mage.Common)**: Removed aiHarnessMode - returns server to stock, improves rebase cleanliness.

**Client (Mage.Client)**:
- `GamePanel.java` - Made non-final, added protected accessors, factory method (extension points)
- `PlayAreaPanel.java` - Added hand panel support for streaming (FUTURE: could be refactored to streaming module)
- `PlayAreaPanelOptions.java` - Added showHandInPlayArea parameter (FUTURE: same as above)
- `SessionHandler.java` - Changed to client-side AI harness detection
- `TablesPanel.java` - Minor refactoring for headless client types
- `AiHarnessConfig.java` - Configuration for multiple headless types

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
