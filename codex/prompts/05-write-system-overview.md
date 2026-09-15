# Codex Task — Generate Business + Cross-System Architecture Documentation

## Task parameters

```text
TARGET_BUSINESS_DOC: <path under ./design>
TARGET_CONSISTENCY_DOC: <path under ./design, or SAME if intentionally combined>
OPTIONAL_END_TO_END_FLOW_DOC: <path under ./design or SAME>
```

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. verified Wallet module overview + relevant detailed docs
4. verified Ledger module overview + relevant detailed docs
5. relevant common-util overview/design docs
6. verified performance/observability docs only where needed

## Goal

Build the documentation layer that explains **why the overall system is designed this way and how Wallet + Ledger cooperate end-to-end**.

This should be written only after both major subsystems have a sufficiently verified knowledge base.

All target documents and assets must live under `./design`. Links may point only to other files under `./design`, using relative paths. Do not cite or direct readers to external repository documentation; restate the validated context needed to understand the system.

Do not regenerate subsystem implementation details. Link to their canonical documents under `./design`.

## Business/system overview

Where supported, explain:

- what z-exchange is
- core business/domain concepts
- why Wallet and Ledger are separated
- wallet vs ledger responsibilities
- balance vs accounting record concepts
- transaction lifecycle
- reservation / consume / release / transfer semantics
- financial correctness requirements
- important business invariants
- major end-to-end flows
- system/module architecture
- infrastructure roles

Explain **why** important domain models exist where the rationale is supported or can be clearly labeled as an engineering inference.

## Wallet ↔ Ledger consistency

This is a P0 document/topic.

Explicitly analyze the actual implementation's behavior for:

- duplicated API requests
- duplicated Kafka messages
- lost messages
- DB/Kafka dual-write inconsistency
- Wallet succeeds / Ledger fails
- Ledger succeeds / Wallet finalization fails
- retry after partial failure
- consumer crash
- timeout with unknown result
- concurrent Wallet operations
- stale cache
- balance vs ledger inconsistency

Describe the relevant:

- local transaction boundaries
- cross-service consistency model
- idempotency boundaries
- unique constraints
- Redis acceleration vs source of truth
- transactional outbox
- Kafka delivery semantics
- application-level deduplication
- state transitions
- retry/recovery
- reconciliation, only if actually implemented

For each meaningful risk, distinguish:

```text
Prevented by invariant/transaction/constraint
Detected and recoverable
Mitigated but not eliminated
Not currently handled
Unknown / needs confirmation
```

A useful table format is:

| Failure/risk | Detection/prevention | Recovery | Current guarantee | Remaining risk |
|---|---|---|---|---|

## End-to-end diagrams

Prefer a small number of high-value diagrams such as:

- system architecture
- Wallet → Ledger transaction sequence
- transaction/reservation state transition
- outbox/retry failure sequence

Do not duplicate lower-level diagrams unless the cross-system view adds meaning.

## End-of-task actions

1. Write/update the target business/system docs.
2. Update `Cross-system synthesis readiness` in the manifest.
3. Verify all cross-service claims against the canonical subsystem docs and source where necessary.
4. Mark any contradicted canonical docs `NEEDS_REVERIFY` rather than smoothing over the inconsistency.
5. Preserve P0 unknowns as explicit TODOs.
6. Record any interview-useful confirmation resolved with the user in `./codex/documentation/qa.md`; do not convert a stated design intention into a current cross-system guarantee.
