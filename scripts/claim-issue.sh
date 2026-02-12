#!/bin/bash
# Claim an issue by creating a draft PR. Lowest PR number wins ties.
#
# Usage:
#   scripts/claim-issue.sh <issue-filename>   Claim an issue
#   scripts/claim-issue.sh --list             List already-claimed issues
#
# Exit codes:
#   0  Claimed successfully (or --list succeeded)
#   1  Already claimed or lost race
#   2  Bad input

set -eo pipefail

if [ "$1" = "--list" ]; then
    gh pr list --state open --json body --jq '.[].body' 2>/dev/null \
        | tr -d '\r' \
        | sed -n 's/^claim: //p' \
        | sort -u
    exit 0
fi

ISSUE="${1%.json}"

if [ -z "$ISSUE" ]; then
    echo "Usage: scripts/claim-issue.sh <issue-filename>" >&2
    echo "       scripts/claim-issue.sh --list" >&2
    exit 2
fi

if [ ! -f "issues/$ISSUE.json" ]; then
    echo "Error: issues/$ISSUE.json not found" >&2
    exit 2
fi

TITLE=$(jq -r '.title' "issues/$ISSUE.json")
BRANCH=$(git branch --show-current)

# Ensure at least one commit ahead of master so the PR can be created
if [ -z "$(git log origin/master..HEAD --oneline 2>/dev/null)" ]; then
    git commit --allow-empty -m "Claim: $TITLE"
fi

# Push current branch
git push -u origin "$BRANCH"

# Create draft PR with claim marker as the first line of the body
PR_URL=$(gh pr create --draft \
    --base master \
    --title "Solve: $TITLE" \
    --body "claim: $ISSUE")

OUR_PR=$(echo "$PR_URL" | grep -oE '[0-9]+$')
echo "Created draft PR #$OUR_PR: $PR_URL"

# Race resolution: lowest PR number claiming this issue wins.
WINNER=$(gh pr list --state open --json number,body \
    --jq "[.[] | select(.body | startswith(\"claim: ${ISSUE}\")) | .number] | sort | .[0]")

if [ "$WINNER" != "null" ] && [ -n "$WINNER" ] && [ "$WINNER" != "$OUR_PR" ]; then
    echo "Lost race: PR #$WINNER already claims $ISSUE" >&2
    gh pr close "$OUR_PR"
    exit 1
fi

echo "Claimed $ISSUE (PR #$OUR_PR)"
echo "Branch: $BRANCH"
echo "Push: git push origin $BRANCH"
