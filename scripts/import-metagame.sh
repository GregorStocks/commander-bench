#!/usr/bin/env bash
# Scrape top decks from MTGGoldfish metagame pages and import them as .dck files.
#
# Usage:
#   scripts/import-metagame.sh <format> [count]
#
# Examples:
#   scripts/import-metagame.sh legacy        # Import top 20 Legacy decks
#   scripts/import-metagame.sh modern 15     # Import top 15 Modern decks
#   scripts/import-metagame.sh standard 20   # Import top 20 Standard decks

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMPORT_DECK="$SCRIPT_DIR/import-deck.sh"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <format> [count]"
    echo "  format: legacy, modern, standard"
    echo "  count:  number of decks to import (default: 20)"
    exit 1
fi

FORMAT="$1"
COUNT="${2:-20}"
DECK_DIR="$PROJECT_ROOT/Mage.Client/release/sample-decks"

case "$FORMAT" in
    legacy)  OUTPUT_DIR="$DECK_DIR/Legacy" ;;
    modern)  OUTPUT_DIR="$DECK_DIR/Modern" ;;
    standard) OUTPUT_DIR="$DECK_DIR/Standard" ;;
    *)
        echo "Error: unknown format '$FORMAT'. Use: legacy, modern, standard"
        exit 1
        ;;
esac

mkdir -p "$OUTPUT_DIR"

echo "Fetching $FORMAT metagame from MTGGoldfish..."

# Scrape metagame page for archetype URLs (paper tab).
# The page lists archetypes in order of meta share (most popular first).
# Use awk to deduplicate while preserving that order.
METAGAME_URL="https://www.mtggoldfish.com/metagame/${FORMAT}/full#paper"
ARCHETYPE_URLS=$(curl -sL "$METAGAME_URL" \
    | grep -oE "/archetype/${FORMAT}-[a-z0-9-]+" \
    | awk '!seen[$0]++' \
    | head -n "$COUNT")

if [ -z "$ARCHETYPE_URLS" ]; then
    echo "Error: no archetypes found on $METAGAME_URL"
    exit 1
fi

echo "Found archetypes:"
echo "$ARCHETYPE_URLS"
echo ""

IMPORTED=0
for ARCHETYPE_PATH in $ARCHETYPE_URLS; do
    ARCHETYPE_NAME=$(echo "$ARCHETYPE_PATH" | sed "s|/archetype/${FORMAT}-||")

    # Get first deck URL from archetype page
    ARCHETYPE_URL="https://www.mtggoldfish.com${ARCHETYPE_PATH}#paper"
    DECK_ID=$(curl -sL "$ARCHETYPE_URL" | grep -oE '/deck/[0-9]+' | head -1 | grep -oE '[0-9]+')

    if [ -z "$DECK_ID" ]; then
        echo "WARNING: no deck found for $ARCHETYPE_NAME, skipping"
        continue
    fi

    # Strip trailing UUIDs and numeric disambiguators from archetype slugs
    # e.g. "4c-reanimator-70c5fc5f-0149-4242-8b1c-dd0b72eeb297" -> "4c-reanimator"
    # e.g. "death-s-shadow-472" -> "death-s-shadow"
    CLEAN_NAME=$(echo "$ARCHETYPE_NAME" | sed -E 's/-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$//' | sed -E 's/-[0-9]+$//')

    # Convert slug to Title-Case filename
    FILENAME=$(echo "$CLEAN_NAME" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1' | sed 's/ /-/g')
    OUTPUT_FILE="$OUTPUT_DIR/${FILENAME}.dck"

    if [ -f "$OUTPUT_FILE" ]; then
        echo "SKIP: $OUTPUT_FILE already exists"
        IMPORTED=$((IMPORTED + 1))
        continue
    fi

    DECK_URL="https://www.mtggoldfish.com/deck/${DECK_ID}"
    echo "Importing: $ARCHETYPE_NAME -> $OUTPUT_FILE (deck $DECK_ID)"

    if "$IMPORT_DECK" "$DECK_URL" "$OUTPUT_FILE"; then
        IMPORTED=$((IMPORTED + 1))
    else
        echo "WARNING: failed to import $ARCHETYPE_NAME"
    fi

    # Rate limit Scryfall API
    sleep 0.5
done

echo ""
echo "Done! Imported $IMPORTED/$COUNT decks to $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"/*.dck 2>/dev/null || true
