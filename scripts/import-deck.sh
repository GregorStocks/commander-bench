#!/usr/bin/env bash
# Download a deck from MTGGoldfish and save it as a .txt file for XMage.
#
# Usage:
#   scripts/import-deck.sh <mtggoldfish-url> <output-file>
#
# Example:
#   scripts/import-deck.sh https://www.mtggoldfish.com/deck/7616949 Mage.Client/release/sample-decks/Legacy/Sneak-and-Show.txt

set -euo pipefail

if [ $# -ne 2 ]; then
    echo "Usage: $0 <mtggoldfish-url> <output-file>"
    echo "Example: $0 https://www.mtggoldfish.com/deck/7616949 output.txt"
    exit 1
fi

URL="$1"
OUTPUT="$2"

# Extract deck ID from URL (supports /deck/NNNN and /deck/NNNN#paper etc.)
DECK_ID=$(echo "$URL" | grep -oE '/deck/[0-9]+' | grep -oE '[0-9]+')

if [ -z "$DECK_ID" ]; then
    echo "Error: could not extract deck ID from URL: $URL"
    exit 1
fi

DOWNLOAD_URL="https://www.mtggoldfish.com/deck/download/${DECK_ID}"

mkdir -p "$(dirname "$OUTPUT")"
curl -sL "$DOWNLOAD_URL" -o "$OUTPUT"

MAIN_COUNT=$(sed '/^$/q' "$OUTPUT" | grep -cE '^[0-9]' || true)
SB_COUNT=$(sed -n '/^$/,$p' "$OUTPUT" | grep -cE '^[0-9]' || true)

echo "Saved ${MAIN_COUNT} main / ${SB_COUNT} sideboard cards to ${OUTPUT}"
