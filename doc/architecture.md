# Architecture

## Philosophy: Stock Server, Smart Clients

This fork should maintain **zero modifications to the XMage server**. All AI harness functionality should be implemented in:

1. **puppeteer** - Python orchestration layer
2. **Mage.Client** - GUI client modifications
3. **Mage.Client.Headless** - Skeleton/headless client

### Why?

- **Easier to stay in sync with upstream** - We can pull from xmage/master without merge conflicts in server code
- **Cleaner separation** - The server is "dumb" infrastructure; intelligence lives in clients
- **Simpler deployment** - Can use stock XMage server releases

### What the stock server already provides

The stock XMage server's `testMode` (`-Dxmage.testMode=true`) already provides:

- Skipped password verification during login
- Skipped deck validation
- Extended idle timeouts (1 hour instead of seconds)
- Skipped user stats DB operations

This is sufficient for AI harness use cases.

### Client-side harness features

All AI harness automation should be client-triggered:

| Feature | Implementation |
|---------|---------------|
| Auto-connect | Client system property `-Dxmage.aiHarness.autoConnect=true` |
| Auto-start game | Client system property `-Dxmage.aiHarness.autoStart=true` |
| Player config | Client system property `-Dxmage.aiHarness.playersConfig={json}` |
| Auto-watch | Client detects when table is ready and starts watching |

### Server API usage

Where possible, puppeteer should use the server's existing RMI API rather than adding new server code. The MageServer interface provides:

- `roomCreateTable(sessionId, roomId, MatchOptions)` - Create tables
- `roomJoinTable(sessionId, roomId, tableId, ...)` - Join tables
- `matchStart(sessionId, roomId, tableId)` - Start matches
- `getServerState()` - Query server capabilities

### Current state

As of this writing, the fork maintains **minimal changes** to server code:

- `Mage.Server/` - two fixes to skip user stats in testMode (workaround for Apple Silicon SQLite crash)
- `Mage.Common/` - completely stock XMage

All AI harness functionality is implemented in:

- `Mage.Client/` - client-side automation (auto-connect, auto-start, player config)
- `Mage.Client.Headless/` - skeleton/headless client for AI players
- `puppeteer/` - Python orchestration layer
