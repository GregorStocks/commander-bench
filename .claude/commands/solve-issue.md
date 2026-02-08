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
4. Implement the fix
5. Update tests to expect the correct behavior
6. Run `make lint` to verify
7. Delete the issue file (e.g., `rm issues/the-issue-name.json`) and **include the deletion in the commit** — the issue removal must ship with the fix
8. **Document ALL issues you discover** during exploration, even if you're only fixing one. Future Claudes benefit from this documentation!
9. Create a PR, then stop - leave remaining issues for the next Claude

## Is It Worth Fixing?

Not every quirk deserves a fix. For issues that seem one-in-a-million or where it's not realistically possible to determine the original author's intent, it's fine to give up and handle it gracefully. Being correct on fewer things is better than being _wrong_.

## Important

- One issue per PR - keeps PRs small and reviewable
- Stop after creating the PR - don't chain multiple fixes
