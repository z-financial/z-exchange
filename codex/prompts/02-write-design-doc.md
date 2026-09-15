# Codex Task — Write One Detailed Design Document

> Reusable prompt. Replace the task parameters before running.

## Task parameters

```text
DESIGN_ID: <D-xxx>
TARGET_DOC: <path under ./design from documentation-manifest.md>
OPTIONAL_SCOPE_NOTE: <optional extra constraint>
```

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. existing `./design` docs explicitly listed as dependencies for `DESIGN_ID`

## Goal

Investigate and document **only the bounded design domain identified by `DESIGN_ID`**.

Do not attempt to understand or rewrite the entire repository.
Do not regenerate the repository-root `./README.md`.
Do not rewrite unrelated existing documents.

`TARGET_DOC` must be under `./design`. The generated document and any assets it uses must remain within that tree.

Use the manifest's evidence pointers as a starting map, but inspect the actual implementation deeply enough to produce a technically defensible document.

## Before writing

1. Set this design's manifest status to `IN_PROGRESS`.
2. Read its questions, evidence pointers, dependencies, and boundary.
3. Inspect the relevant current source/config/tests/schema/evidence.
4. Expand the evidence list when you discover important additional implementation locations.
5. Check old or external docs only as supporting research context; validate important claims against implementation and restate supported information without citing or linking those documents from `TARGET_DOC`.

If investigation shows the proposed document boundary is wrong, update the manifest before writing. You may merge/split a design only when there is a clear architectural reason; record the decision.

## Document objective

Write an interviewer-oriented design document that explains the **engineering problem and reasoning**, not merely code structure.

Where applicable, cover:

```text
Problem / business context
Requirements and invariants
Constraints
Architecture / responsibility boundary
Data/state model
Main flow(s)
State transitions
Transaction boundaries
Concurrency/locking behavior
Idempotency/deduplication
Correctness guarantees
Failure scenarios
Retry/recovery behavior
Interactions with other services/infrastructure
Trade-offs / alternatives
Observability
Performance considerations
Limitations / future enhancements
```

Do not include sections that are irrelevant to the selected design.

## Evidence discipline

For important implementation claims:

- identify the relevant class/method/table/config/test in the prose when helpful;
- make the claim no stronger than the evidence;
- distinguish actual guarantees from mitigation/best-effort behavior;
- distinguish currently used infrastructure from reusable-but-unused infrastructure;
- never invent benchmark numbers or guarantees.

If a rationale cannot be established from the repository, either:

- label it explicitly as an engineering inference, or
- add `TODO: NEED CONFIRMATION`.

## Cross-document discipline

This document should be the canonical owner of its design domain.

It must be understandable as part of the self-contained `./design` documentation set. It must not link to, cite, or instruct the reader to open documentation outside `./design`, including `./doc`, `./standard`, `./engineering-notes`, `./codex`, or the repository-root `README.md`. Source/config/schema/test symbols may be named as implementation evidence, but the prose must explain the design without requiring those files to be opened.

When another canonical doc owns a related topic:

1. provide only enough summary for local understanding;
2. link to that document with a relative path within `./design`;
3. do not reproduce its full explanation.

If an internal dependency doc is not ready, add a temporary TODO for its future `./design` path rather than linking outside the tree or duplicating speculative content.

## Diagrams

Use a diagram when it materially clarifies:

- architecture
- sequence flow
- state transitions
- failure/recovery flow

Do not add decorative diagrams with no explanatory value.

For a reader-facing document:

- place the primary diagram in the opening overview section when it is the fastest way to establish the mental model;
- keep **Design in one minute** and its primary diagram focused on the intended architecture, responsibilities, main lifecycle, and core guarantees; move design flaws, failure scenarios, recovery boundaries, and limitations to dedicated later sections;
- render important diagrams to SVG under `./design/assets` and embed them directly, rather than publishing Mermaid source that requires renderer support;
- keep responsibility labels concise and focused on what each component owns;
- separate **Trade-offs** from **Future enhancements** when both are present;
- use **Future enhancements** only when the current design is coherent and complete for its stated scope—known correctness gaps or unmet requirements must remain explicit.

## End-of-task actions

1. Write/update `TARGET_DOC`.
2. Update the manifest's:
   - evidence
   - dependencies
   - boundary
   - discovered invariants
   - failure scenarios
   - uncertainty/TODOs
3. Set status to `DRAFTED` — **not `VERIFIED`**.
4. Record any new design domains discovered while investigating, but do not expand this task into writing them.
5. Provide a concise handoff stating what the verification pass should pay particular attention to.
6. Record any interview-useful confirmation resolved with the user in `./codex/documentation/qa.md`; preserve the original answer and do not strengthen it in the polished version.
