#!/bin/bash
# Check Maven build cache effectiveness
# Usage: ./scripts/check-maven-cache.sh [maven-args]
# Examples:
#   ./scripts/check-maven-cache.sh                    # Run default install
#   ./scripts/check-maven-cache.sh -pl Mage           # Build specific module
#   ./scripts/check-maven-cache.sh -DskipTests        # Skip tests

set -e

CACHE_DIR=~/.m2/build-cache
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=== Maven Build Cache Check ==="
echo

# Show cache directory stats
echo "Cache directory: $CACHE_DIR"
if [ -d "$CACHE_DIR" ]; then
    CACHE_SIZE=$(du -sh "$CACHE_DIR" 2>/dev/null | cut -f1)
    CACHE_MODULES=$(find "$CACHE_DIR" -name "buildinfo.xml" 2>/dev/null | wc -l | tr -d ' ')
    echo "  Size: $CACHE_SIZE"
    echo "  Cached builds: $CACHE_MODULES"
else
    echo "  (directory does not exist yet)"
fi
echo

# Create temp file for build output
TEMP_OUTPUT=$(mktemp)
trap "rm -f $TEMP_OUTPUT" EXIT

echo "Running: mvn install -X $*"
echo "(capturing output for analysis...)"
echo

# Run maven with debug output, show progress, capture to temp file
cd "$PROJECT_DIR"
if mvn install -X "$@" 2>&1 | tee "$TEMP_OUTPUT"; then
    BUILD_SUCCESS=true
else
    BUILD_SUCCESS=false
fi

echo
echo "=== Cache Analysis ==="
echo

# Count cache hits and misses from the output
# The maven-build-cache-extension logs these patterns:
#   - "Found cached build, restoring" for hits
#   - "Saved Build to local file" for misses (new cache entries)
CACHE_HITS=$(grep "Found cached build, restoring" "$TEMP_OUTPUT" 2>/dev/null | wc -l | tr -d '[:space:]')
CACHE_MISSES=$(grep "Saved Build to local file" "$TEMP_OUTPUT" 2>/dev/null | wc -l | tr -d '[:space:]')
# Ensure we have valid integers
CACHE_HITS=${CACHE_HITS:-0}
CACHE_MISSES=${CACHE_MISSES:-0}

echo -e "Cache hits:   ${GREEN}$CACHE_HITS${NC}"
echo -e "Cache misses: ${YELLOW}$CACHE_MISSES${NC}"

TOTAL=$((CACHE_HITS + CACHE_MISSES))
if [ "$TOTAL" -gt 0 ]; then
    HIT_RATE=$((CACHE_HITS * 100 / TOTAL))
    echo "Hit rate:     $HIT_RATE%"
fi

echo

# Show some detail about what was cached
if [ "$CACHE_HITS" -gt 0 ]; then
    echo "Modules restored from cache:"
    grep "Found cached build, restoring" "$TEMP_OUTPUT" 2>/dev/null | head -10 | sed 's/^/  /'
    echo
fi

if [ "$CACHE_MISSES" -gt 0 ]; then
    echo "Modules saved to cache (cache miss):"
    grep "Saved Build to local file" "$TEMP_OUTPUT" 2>/dev/null | head -10 | sed 's/^/  /'
    echo
fi

if [ "$TOTAL" -eq 0 ]; then
    echo -e "${YELLOW}No cache activity detected in build output.${NC}"
    echo "This could mean:"
    echo "  - The build cache extension isn't active"
    echo "  - The grep patterns don't match this Maven version's output"
    echo "  - Check the raw output with: mvn install -X 2>&1 | grep -i cache"
    echo
fi

# Final status
if [ "$BUILD_SUCCESS" = true ]; then
    echo -e "${GREEN}Build completed successfully${NC}"
else
    echo -e "${RED}Build failed${NC}"
    exit 1
fi
