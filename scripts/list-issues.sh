#!/usr/bin/env bash
# List all issues sorted by priority (lowest number = highest priority)
for f in issues/*.json; do
  echo "$(basename "$f" .json): $(jq -r '[.priority, .title] | @tsv' "$f")"
done | sort -t: -k2 -n
