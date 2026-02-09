#!/usr/bin/env bash
# Download a deck from MTGGoldfish and save it as a .dck file for XMage.
# Uses Scryfall API to resolve card names to set codes and collector numbers.
#
# Usage:
#   scripts/import-deck.sh <mtggoldfish-url> <output-file>
#
# Example:
#   scripts/import-deck.sh https://www.mtggoldfish.com/deck/7616949 Mage.Client/release/sample-decks/Legacy/Sneak-and-Show.dck

set -euo pipefail

if [ $# -ne 2 ]; then
    echo "Usage: $0 <mtggoldfish-url> <output-file>"
    echo "Example: $0 https://www.mtggoldfish.com/deck/7616949 output.dck"
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

# Download plain text deck list, then resolve card names via Scryfall
# and output in .dck format: N [SET:NUM] Card Name
DECK_TEXT=$(curl -sL "$DOWNLOAD_URL")

mkdir -p "$(dirname "$OUTPUT")"

uv run --project puppeteer python -c "
import json, re, sys, urllib.request

deck_text = sys.stdin.read()
lines = deck_text.strip().split('\n')

# Parse card names (dedup for Scryfall lookup)
cards = {}  # name -> [(count, is_sideboard)]
sideboard = False
for line in lines:
    line = line.strip()
    if not line:
        sideboard = True
        continue
    m = re.match(r'^(\d+)\s+(.+)$', line)
    if m:
        count, name = int(m.group(1)), m.group(2).strip()
        cards.setdefault(name, []).append((count, sideboard))

# Scryfall collection lookup (up to 75 cards per request)
names = list(cards.keys())
resolved = {}  # name -> (set_code, collector_number)
for i in range(0, len(names), 75):
    batch = names[i:i+75]
    body = json.dumps({'identifiers': [{'name': n} for n in batch]}).encode()
    req = urllib.request.Request(
        'https://api.scryfall.com/cards/collection',
        data=body,
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    for card in data.get('data', []):
        resolved[card['name']] = (card['set'].upper(), card['collector_number'])
    for nf in data.get('not_found', []):
        print(f'WARNING: card not found: {nf.get(\"name\", nf)}', file=sys.stderr)

# Output .dck format
out_lines = []
sb_lines = []
for name, entries in cards.items():
    if name not in resolved:
        continue
    set_code, num = resolved[name]
    for count, is_sb in entries:
        line = f'{count} [{set_code}:{num}] {name}'
        if is_sb:
            sb_lines.append(f'SB: {line}')
        else:
            out_lines.append(line)

with open(sys.argv[1], 'w') as f:
    for line in out_lines:
        f.write(line + '\n')
    for line in sb_lines:
        f.write(line + '\n')

print(f'Saved {len(out_lines)} main / {len(sb_lines)} sideboard to {sys.argv[1]}')
" "$OUTPUT" <<< "$DECK_TEXT"
