# Optional AGENTS.md snippet for documentation work

> Do not replace an existing `AGENTS.md` blindly. Merge the following section into it if useful.

## Documentation work

For any task that creates, verifies, restructures, or summarizes z-exchange documentation:

1. Read `./codex/documentation/DOCUMENTATION_CONTRACT.md` first.
2. Read `./codex/documentation/documentation-manifest.md` before deciding scope.
3. Read `./codex/documentation/qa.md` before asking, resolving, or recording confirmation questions, and update an existing matching entry rather than duplicating it.
4. Treat the manifest as persistent documentation state and update affected entries during the task.
5. When the user resolves an interview-useful confirmation question, record the question, their original answer, a faithful polished answer, the related design/module, and likely follow-up questions in `qa.md` during the same task. Never add or retain an entry marked `<NO NEED TO DOCUMENT THIS ANSWER>`.
6. Preserve the meaning of the user's answer; never invent details or turn intended work into current behavior. `qa.md` is internal and must not be linked from `./design`.
7. Use the repository-root `./README.md` as the final documentation entry page; generate every deeper reader-facing document and asset under `./design`.
8. The root README may link only into `./design`. A document under `./design` may link only to another file under `./design`; it must not refer readers to documentation in `./doc`, `./standard`, `./engineering-notes`, or `./codex`.
9. External repository documents may guide investigation, but validate and restate supported information inside the appropriate `./design` document so the published set is self-contained.
10. Prefer bounded work on one design/module over re-reading the whole repository.
11. Prefer current implementation/config/schema/tests over stale documentation when claims conflict.
12. Never invent behavior, guarantees, benchmark results, reconciliation, or infrastructure adoption.
13. Do not mark detailed documentation `VERIFIED` until an explicit verification pass checks important claims against current implementation/evidence.
14. Higher-level docs should synthesize verified lower-level docs under `./design` and link to canonical owners instead of duplicating them.
15. The repository-root `./README.md` should be generated only after the important detailed/module/cross-system documentation is sufficiently verified.
16. Preserve explicit `TODO: NEED CONFIRMATION`, `BLOCKED`, and `NEEDS_REVERIFY` states when uncertainty remains.
