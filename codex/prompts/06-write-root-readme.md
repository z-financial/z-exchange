# Codex Task — Generate the Documentation Entry README Last

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. verified module/subsystem overviews
4. business/system overview
5. Wallet ↔ Ledger consistency overview
6. verified observability/performance summaries where available

## Preconditions

Check the manifest's root `README.md` readiness section.

If it is not ready because a critical P0 design is missing or contradictory, do not hide the problem. Either:

- resolve the bounded contradiction from canonical docs/source, or
- leave an explicit README-safe omission and record the blocker in the manifest.

Do not compensate by scanning the entire repository and inventing a new system interpretation from scratch.

## Goal

Create a concise interviewer-oriented repository-root `./README.md` that makes the project understandable and worth a deeper look.

Every documentation link and asset used by `./README.md` must resolve under `./design`; it must not cite or direct readers to documentation elsewhere in the repository.

The README should act as:

```text
project pitch grounded in engineering evidence
+ architecture map
+ strongest verified highlights
+ navigation to deeper documentation
```

It should not be a giant design document.

## At minimum cover

- what z-exchange is
- business context
- compact architecture view
- major services/modules
- important technologies
- strongest engineering/design highlights
- Wallet Service summary
- Ledger Service summary
- common-util/infrastructure summary
- Wallet ↔ Ledger correctness/reliability highlights
- performance/observability highlights **only when verified**
- concise limitations/future-work signal if useful

## Documentation Guide

Provide obvious navigation for readers who want to deep dive into:

- business/domain design
- Wallet design
- Ledger design
- Wallet ↔ Ledger consistency
- transaction lifecycle
- data model/invariants
- idempotency
- concurrency/locking
- Kafka/outbox/recovery
- Redis/caching
- MySQL/transactions
- common-util
- observability
- stress testing/performance

Prefer linking to overview docs first, with direct links to P0 detailed docs where useful.

## Selection principle

Do not try to mention every implemented feature.

Select the strongest **verified engineering signals** that make the project credible for backend/distributed-system interviews.

Prioritize transaction correctness/reliability over `createWallet`/`createAccount` initialization APIs.

Avoid marketing-style claims such as "highly scalable", "exactly once", "production grade", or "zero data loss" unless the documentation contains evidence for the precise claim.

## README structure guidance

A reasonable structure is:

```text
# z-exchange
short description

## Why this project / Engineering highlights
## Architecture
## Core transaction model
## Reliability & correctness
## Performance & observability
## Modules
## Documentation Guide
## Running / local development (only if useful and already supported)
```

Adapt the structure to the actual project.

## End-of-task actions

1. Write/update the repository-root `./README.md`.
2. Check that every link resolves to a file under `./design`.
3. Check that every strong claim has a deeper canonical explanation under `./design`; keep implementation-verification provenance in the manifest rather than linking to external documentation.
4. Remove duplication that belongs in detailed docs.
5. Update manifest root-readiness/status notes.
6. Record any interview-useful confirmation resolved with the user in `./codex/documentation/qa.md`; keep the Q&A log internal and do not link it from `./README.md`.
