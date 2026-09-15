# Codex Task — Generate One Module / Subsystem Overview

> Reusable for Wallet Service, Ledger Service, or common-util.

## Task parameters

```text
MODULE: <Wallet Service | Ledger Service | common-util>
TARGET_DOC: <path under ./design>
```

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. the `VERIFIED` detailed design docs relevant to `MODULE`
4. `DRAFTED/BLOCKED` relevant docs only when their uncertainty must be represented

## Goal

Create a **5–10 minute subsystem overview** that lets an interviewer understand the module's role, architecture, important flows, and strongest engineering decisions, while providing links to deeper canonical design docs.

Write `TARGET_DOC` and any required assets under `./design`. Link only to other `./design` files, using relative links. Do not cite or direct readers to documentation elsewhere in the repository; incorporate the necessary validated explanation so this documentation set remains self-contained.

This is a synthesis task, not a second full reverse-engineering pass.

Use verified design docs as the primary knowledge base. Return to source/config/tests only when:

- the overview requires a claim not supported by those docs;
- a critical summary claim looks inconsistent;
- the manifest says re-verification is needed.

Do not re-scan the whole repository without a concrete reason.

## Content guidance

Where applicable, cover:

- subsystem purpose and business responsibility
- responsibility boundaries / what it does not own
- architecture / important components
- domain/data model at overview depth
- primary transaction/read/write flows
- correctness/invariants
- concurrency model
- idempotency/reliability
- infrastructure dependencies
- failure/recovery behavior
- observability
- performance considerations/evidence
- known limitations/future work
- Documentation Guide / deep-dive links

### Wallet Service emphasis

Prioritize transaction lifecycle, balances, reservations, reserve/consume/release/transfer, locking/concurrency, idempotency, caching, and Ledger interaction.

### Ledger Service emphasis

Prioritize posting lifecycle, accounting model/invariants, database correctness, idempotency, Kafka/outbox, failure/recovery, and performance.

### common-util emphasis

Explain reusable capabilities and explicitly classify each important capability as:

```text
CURRENTLY USED
PARTIALLY ADOPTED
AVAILABLE BUT CURRENTLY UNUSED
POSSIBLE FUTURE USAGE
```

## Avoid duplication

Do not copy entire sections from detailed docs.

The module overview should answer:

> What should I understand about this subsystem before choosing a deep dive?

For detailed mechanisms, summarize briefly and link to the canonical doc under `./design`.

## End-of-task actions

1. Write/update `TARGET_DOC`.
2. Update manifest `Module synthesis readiness`.
3. Add/update links between this overview and detailed docs.
4. Preserve unresolved uncertainty explicitly.
5. If this synthesis reveals a contradiction between verified docs, do not silently choose one; mark affected docs `NEEDS_REVERIFY` and record the conflict.
6. Record any interview-useful confirmation resolved with the user in `./codex/documentation/qa.md`, preserving the meaning of the original answer.
