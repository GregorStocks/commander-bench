#!/usr/bin/env bash
# Conductor workspace archive cleanup script

set -euo pipefail

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found; skipping ai-harness cleanup"
  exit 0
fi

if ! uv run --project puppeteer python -m puppeteer.cleanup_orphans; then
  echo "ai-harness cleanup failed; continuing archive"
  exit 0
fi
