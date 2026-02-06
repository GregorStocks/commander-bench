#!/usr/bin/env bash
# Conductor workspace archive cleanup script

set -euo pipefail

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found; skipping ai-harness cleanup"
  exit 0
fi

if ! uv run --project puppeteer python -m puppeteer.cleanup_orphans; then
  echo "ai-harness cleanup failed; continuing archive"
fi

# Copy game logs to permanent storage
LOGS_DIR=".context/ai-harness-logs"
ARCHIVE_DIR="$HOME/mage-logs"

if [ -d "$LOGS_DIR" ]; then
  mkdir -p "$ARCHIVE_DIR"
  copied=0
  for game_dir in "$LOGS_DIR"/game_*/; do
    [ -d "$game_dir" ] || continue
    game_name=$(basename "$game_dir")
    if [ ! -d "$ARCHIVE_DIR/$game_name" ]; then
      cp -r "$game_dir" "$ARCHIVE_DIR/$game_name"
      copied=$((copied + 1))
    fi
  done
  if [ "$copied" -gt 0 ]; then
    echo "Archived $copied game(s) to $ARCHIVE_DIR"
  else
    echo "No new games to archive"
  fi
fi
