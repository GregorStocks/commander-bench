---
name: solve-bead
description: Pick and solve exactly one bead (bd) issue, following the repo workflow and closing the bead before the PR.
---

# Solve a Bead

Pick and solve exactly **one** bead, then create a PR.

## Workflow

1. Run `bd list` to see open beads
2. Pick **one** issue that seems worth fixing (see criteria below)
3. Implement the fix
4. Update tests to expect the correct behavior
5. Run `make test` and `make lint` to verify
6. Run `make pipeline` and spot-check the diff for regressions
7. Close the bead with `bd close <bead-id>` (do this before committing so it's included in the PR)
8. **Document ALL issues you discover** during exploration, even if you're only fixing one. Future Claudes benefit from this documentation!
9. Create a PR, then stop - leave remaining issues for the next Claude

## Is It Worth Fixing?

Not every quirk deserves a fix. For issues that seem one-in-a-million (e.g. an uncommon typo) or where it's not realistically possible to determine the original author's intent, it's fine to give up and handle it gracefully. Being correct on fewer things is better than being _wrong_.

## Important

- One issue per PR - keeps PRs small and reviewable
- Stop after creating the PR - don't chain multiple fixes
