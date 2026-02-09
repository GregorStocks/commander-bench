# Solve an Issue

Pick and solve exactly **one** issue, then create a PR.

## Workflow

1. Rebase against origin/master:
   ```bash
   git fetch origin && git rebase origin/master
   ```
2. List open issues:
   ```bash
   for f in issues/*.json; do echo "$(basename "$f" .json): $(jq -r '[.priority, .title] | @tsv' "$f")"; done | sort -t: -k2 -n
   ```
3. Pick **one** issue, preferring higher-priority (lower number) issues first (see criteria below)
4. **STOP and confirm with the user before doing anything else** — state which issue you picked and why, then **end your turn and wait for approval**. Do NOT explore code, read implementation files, or start planning until the user confirms. Multiple agents run in parallel, so the user needs to redirect you if someone else already claimed that issue.
5. Implement the fix
6. Update tests to expect the correct behavior
7. Run `make lint` to verify
8. Delete the issue file (e.g., `rm issues/the-issue-name.json`) and **include the deletion in the commit** — the issue removal must ship with the fix
9. **Document ALL issues you discover** during exploration, even if you're only fixing one. Future Claudes benefit from this documentation!
10. Create a PR, then stop - leave remaining issues for the next Claude

## Is It Worth Fixing?

Not every quirk deserves a fix. For issues that seem one-in-a-million or where it's not realistically possible to determine the original author's intent, it's fine to give up and handle it gracefully. Being correct on fewer things is better than being _wrong_.

## Important

- One issue per PR - keeps PRs small and reviewable
- Stop after creating the PR - don't chain multiple fixes
