#!/usr/bin/env python3
"""List all issues sorted by priority (lowest number = highest priority).

Usage:
    list-issues.py
"""

import json
from pathlib import Path

ISSUES_DIR = Path("issues")


def main() -> None:
    assert ISSUES_DIR.is_dir(), f"Issues directory not found: {ISSUES_DIR}"

    issues = []
    for f in sorted(ISSUES_DIR.glob("*.json")):
        data = json.loads(f.read_text())
        issues.append((f.stem, data.get("priority", 999), data["title"]))

    issues.sort(key=lambda i: i[1])

    for stem, priority, title in issues:
        print(f"{stem}: {priority}\t{title}")


if __name__ == "__main__":
    main()
