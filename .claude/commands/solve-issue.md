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
   git ls-remote --heads origin 'refs/heads/solve/*' 2>/dev/null | awk -F'/' '{print $NF}'
   ```
   Any issue whose filename appears in this list is taken — skip it.
4. Pick **one** unclaimed issue, preferring higher-priority (lower number) first (see criteria below). **Read the issue JSON** and check any preconditions — some issues only apply in specific circumstances. If the issue doesn't apply or isn't actionable, skip it and pick the next one.
5. **Claim the issue** by pushing to a deterministic branch and creating a draft PR:
   ```bash
   git commit --allow-empty -m "Claim: solving <issue-filename>"
   git push origin HEAD:refs/heads/solve/<issue-filename>
   gh pr create --draft \
     --head "solve/<issue-filename>" \
     --base master \
     --title "Solve: <issue title>" \
     --body "Claiming issue: \`<issue-filename>\`"
   ```
   - If the push **succeeds**: you claimed it. Continue to step 6.
   - If the push **fails** (branch already exists): someone else got it first. Re-run step 3 to refresh the claimed list and go back to step 4. Give up after 5 failed attempts and tell the user no unclaimed issues are available.
6. **Enter plan mode** — explore the codebase, design your approach, and present it to the user for feedback before writing any code. This is the user's chance to redirect you if the approach is wrong.
7. Implement the fix. Push progress to the claim branch:
   ```bash
   git push origin HEAD:refs/heads/solve/<issue-filename>
   ```
8. Update tests to expect the correct behavior
9. Run `make check` to verify lint, typecheck, and tests pass
10. Delete the issue file (e.g., `rm issues/<issue-filename>.json`) and **include the deletion in the commit** — the issue removal must ship with the fix
11. **Document ALL issues you discover** during exploration, even if you're only fixing one. Future Claudes benefit from this documentation!
12. Push final changes and mark the PR as ready:
    ```bash
    git push origin HEAD:refs/heads/solve/<issue-filename>
    gh pr ready "solve/<issue-filename>"
    ```
    Then stop — leave remaining issues for the next Claude.

## Abandoning an Issue

If you determine an issue isn't worth fixing after claiming it, clean up your claim:
```bash
gh pr close "solve/<issue-filename>" --delete-branch
```
Then restart from step 2 to pick a different issue.

## Is It Worth Fixing?

Not every quirk deserves a fix. For issues that seem one-in-a-million or where it's not realistically possible to determine the original author's intent, it's fine to give up and handle it gracefully. Being correct on fewer things is better than being _wrong_.

## Important

- One issue per PR — keeps PRs small and reviewable
- Stop after creating the PR — don't chain multiple fixes
- All pushes go to `solve/<issue-filename>` — this is the shared claim branch, not your local branch name
