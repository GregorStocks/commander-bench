# Twitch + OBS Overlay Guide

This repo now exposes a local overlay web server from `Mage.Client.Streaming`.

## Start streaming observer

```bash
make run-dumb
```

Optional overlay controls:

```bash
# Custom overlay port
make run-dumb ARGS="--overlay-port 18080"

# Disable overlay server
make run-dumb ARGS="--no-overlay"
```

Run only a long-lived test server (no observer/client), useful for repeated manual testing:

```bash
make run-staller
# Optional custom port:
make run-staller PORT=18080
```

By default, overlay endpoints are:

- `http://127.0.0.1:17888/` (full overlay page)
- `http://127.0.0.1:17888/video_overlay.html` (transparent video overlay style, pixel-position mode)
- `http://127.0.0.1:17888/?mock=1` (mock data mode for local testing)

If `17888` is already in use, the harness/client will automatically move to the next available port and print the chosen URL.

## Staller personality

`Mage.Client.Headless` now supports a `staller` personality: it makes the same choices as `potato`, but responds slowly and stays connected between games.

Use it in harness config:

```json
{
  "players": [
    {"type": "staller", "name": "Slowpoke", "deck": "random"}
  ]
}
```

Optional JVM override for staller delay:

```bash
-Dxmage.headless.stallerDelayMs=20000
```

## OBS setup

1. Keep XMage streaming client running (`make run-dumb`).
2. In OBS, add your game/window capture source for XMage.
3. Add a Browser Source:
   - URL: `http://127.0.0.1:17888/video_overlay.html` (or the fallback URL printed by harness)
   - Width/Height: match your canvas (for example `1920x1080`)
   - Refresh browser when scene becomes active: enabled
4. If you want to test layout/hover interactions before a real game starts, switch URL to:
   - `http://127.0.0.1:17888/?mock=1`

## Overlay behavior

- The overlay publishes live game state from the streaming observer (`/api/state`).
- `video_overlay.html` uses exported card rectangles from the Swing UI and scales them to the browser source size.
- Cards are hoverable; hover opens a preview panel with card text and image.
- Card image URLs are built from Scryfall metadata in the game state.

## Twitch extension note

`video_overlay.html` is designed to be Twitch-extension-friendly UI-wise and can be tested locally in OBS.

For a production Twitch extension (viewer-side interactive extension), you still need a hosted relay backend that distributes state to all viewers (viewer browsers cannot read the broadcaster's `localhost` overlay endpoint directly).
