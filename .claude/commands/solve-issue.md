# Solve an Issue

Pick and solve exactly **one** issue, then create a PR.

## Workflow

1. Rebase against origin/master:
   ```bash
   git fetch origin && git rebase origin/master
   ```
2. List open issues sorted by priority:
   ```bash
   for f in issues/*.json; do echo "$(basename "$f" .json): $(jq -r '[.priority, .title] | @tsv' "$f")"; done | sort -t: -k2 -n
   ```
3. Check which issues are already claimed by other agents:
   ```bash
   scripts/claim-issue.sh --list
   ```
   Any issue whose filename appears in this list is taken — skip it.
4. Pick **one** unclaimed issue, preferring higher-priority (lower number) first (see criteria below). **Read the issue JSON** and check any preconditions — some issues only apply in specific circumstances. If the issue doesn't apply or isn't actionable, skip it and pick the next one.
5. **Claim the issue** by running:
   ```bash
   scripts/claim-issue.sh <issue-filename>
   ```
   This pushes your branch, creates a draft PR, and checks for race conditions (lowest PR number wins).
   - If the script **succeeds** (exit 0): you claimed it. Continue to step 6.
   - If the script **fails** (exit 1): someone else claimed it first. Re-run step 3 and go back to step 4. Give up after 5 failed attempts and tell the user no unclaimed issues are available.
6. **Enter plan mode** — explore the codebase, design your approach, and present it to the user for feedback before writing any code. This is the user's chance to redirect you if the approach is wrong.
7. Implement the fix. Push progress:
   ```bash
   git push origin HEAD
   ```
8. Update tests to expect the correct behavior
9. Run `make check` to verify lint, typecheck, and tests pass
10. Delete the issue file (e.g., `rm issues/<issue-filename>.json`) and **include the deletion in the commit** — the issue removal must ship with the fix
11. **Document ALL issues you discover** during exploration, even if you're only fixing one. Future Claudes benefit from this documentation!
12. Push final changes, update the PR title/description, and mark it as ready:
    ```bash
    git push origin HEAD
    gh pr edit --title "<concise PR title>" --body "<PR description with summary, test plan>"
    gh pr ready
    ```
    Then stop — leave remaining issues for the next Claude.

## Abandoning an Issue

If you determine an issue isn't worth fixing after claiming it, clean up your claim:
```bash
gh pr close --delete-branch
```
Then restart from step 2 to pick a different issue.

## Is It Worth Fixing?

Not every quirk deserves a fix. For issues that seem one-in-a-million or where it's not realistically possible to determine the original author's intent, it's fine to give up and handle it gracefully. Being correct on fewer things is better than being _wrong_.

## Important

- One issue per PR — keeps PRs small and reviewable
- Stop after creating the PR — don't chain multiple fixes
