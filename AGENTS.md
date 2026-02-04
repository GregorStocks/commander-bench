Use `bd` for task tracking. See `doc/beads.md` and `doc/skills/solve-bead.md`.

## Code Modification Guidelines

Avoid modifying server code (`Mage.Server*`) whenever possible.

Avoid modifying the normal client (`Mage.Client`) that real players use. All observer mode changes should live in `Mage.Client.Streaming` or `Mage.Client.Headless`.

## Python

Always use `uv` for Python. Never use system Python directly.

```bash
# Run a Python script
uv run python script.py

# Run a module
uv run --project puppeteer python -m puppeteer
```
