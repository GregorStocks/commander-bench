#!/usr/bin/env bash
# List all issues with their titles
for f in issues/*.json; do
  echo "$(basename "$f" .json): $(jq -r .title "$f")"
done
