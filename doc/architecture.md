# Architecture

## Philosophy: Stock Server, Minimal Client Changes

This fork should maintain **zero modifications to the XMage server** and **minimal modifications to Mage.Client**. All AI harness functionality should be implemented in:

- `Mage.Client.Streaming` - streaming/observer client (subclasses Mage.Client)
- `Mage.Client.Headless` - headless client for AI harness
- `puppeteer/` - Python orchestration layer

### Why?

- **Easier to stay in sync with upstream** - We can pull from xmage/master without merge conflicts
- **Cleaner separation** - The server is "dumb" infrastructure; intelligence lives in our client modules
- **Simpler deployment** - Can use stock XMage server releases

### Acceptable Baseline Modifications

When absolutely necessary, these types of changes to baseline code are acceptable:

- **Removing code/coupling** - simplifies rebasing
- **Extension points** - making classes non-final, adding protected accessors, factory methods to enable subclassing
- **Dependency version bumps** - for compatibility (e.g., Apple Silicon)

### Discouraged Baseline Modifications

- Adding new fields/methods to UI components
- Adding new features to baseline modules
- Changing baseline behavior

### Current Baseline Modifications (Audit)

**Server:**
- `Mage.Server/pom.xml` - updated sqlite-jdbc for Apple Silicon support
- `Mage.Common/` - completely stock XMage

**Client (Mage.Client):**
- `GamePanel.java` - Made non-final, added protected accessors, factory method (extension points)
- `PlayAreaPanel.java` - Added hand panel support for streaming (FUTURE: refactor to streaming module)
- `PlayAreaPanelOptions.java` - Added showHandInPlayArea parameter (FUTURE: same as above)
- `SessionHandler.java` - Changed to client-side AI harness detection
- `TablesPanel.java` - Minor refactoring for headless client types
- `AiHarnessConfig.java` - Configuration for multiple headless types
