# Codex Task — Discover Designs and Build the Documentation Manifest

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md` (or the repository path where the contract was installed)
2. `./codex/documentation/documentation-manifest.md`

## Goal

Investigate the repository and build an evidence-backed **documentation plan** before writing the high-level documentation.

This task is primarily **discovery and decomposition**, not prose documentation generation.

Do **not** generate the repository-root `./README.md` in this task.
Do **not** generate module overview documents in this task.
Do **not** attempt to fully explain every discovered design in the manifest.

Your main output is an updated `documentation-manifest.md`.

Every proposed reader-facing document and asset path must be under `./design`. Existing documents outside `./design` may be recorded as research evidence in the manifest, but must not become links or reader dependencies in the eventual generated documentation.

## Investigation scope

Inspect, as relevant:

- repository/module structure
- source code
- config
- tests
- database schema/SQL/migrations
- stress-test code/results
- monitoring/dashboard artifacts
- `./standard`
- `./doc`
- `./codex`
- `./engineering-notes`

Existing docs may be stale. Use them to find important topics, but validate important design existence against the current implementation.

If the repository is too large for responsible investigation in one context, work module-by-module and preserve findings in the manifest. Do not compensate by making shallow claims about uninspected areas.

## What to discover

Identify **meaningful engineering/business design domains that deserve documentation**, not every class/package.

At minimum investigate whether the project contains substantial designs around (add more if necessary):

- Wallet business/balance model
- Wallet transaction lifecycle
- reserve / consume / release / transfer
- reservation lifecycle
- Wallet locking/concurrency/conditional updates
- Wallet idempotency
- Wallet caching
- Ledger business/accounting model
- Ledger posting lifecycle
- Ledger correctness/invariants
- Ledger DB transaction/concurrency behavior
- Ledger idempotency
- Kafka integration
- transactional outbox
- retry/failure/recovery
- Wallet ↔ Ledger interaction
- duplicated requests/messages
- message loss / DB-Kafka dual-write risks
- partial-failure recovery
- timeout/unknown result
- application-level deduplication
- common-util capabilities
- observability
- stress testing/performance

Do not assume all of these deserve separate documents. Group related concerns into coherent design domains.

## For every discovered design domain

Update the manifest with:

- stable design ID (`D-xxx`)
- name
- P0/P1/P2/P3 priority
- modules/services involved
- why it matters to the interviewer/project story
- questions the eventual document must answer
- compact implementation evidence pointers:
  - source files/classes/methods
  - config
  - schema/SQL
  - tests
  - monitoring/performance artifacts
- related/dependent designs
- proposed canonical document path under `./design`
- proposed document boundary:
  - what belongs in the doc
  - what should be covered by another canonical document under `./design`
- important uncertainty
- initial status (`DISCOVERED`, or `BLOCKED` if appropriate)

Do not mark anything `VERIFIED` during this planning pass merely because you found some source references.

## Document-boundary rules

Avoid excessive fragmentation.

Bad decomposition:

```text
wallet-locking.md
wallet-conditional-update.md
wallet-race-condition.md
wallet-db-transaction.md
```

when these are all aspects of one coherent Wallet balance/concurrency correctness design.

Prefer substantial documents organized by chapters.

At the same time, do not create giant files that combine unrelated designs merely to reduce file count.

## Priority guidance

P0 should concentrate heavily on Wallet/Ledger transaction and correctness design.

`createWallet` and `createAccount` exist for business completeness. They may appear in domain/context documentation but should normally be P3 or a small subsection unless inspection reveals unexpectedly important design.

## Required end-of-task output

1. Updated `documentation-manifest.md`.
2. Updated proposed documentation tree rooted at `./design`.
3. A prioritized queue of the next detailed-design tasks, in dependency-aware order.
4. List of important uncertainties that could affect the documentation architecture.
5. If the user resolves an interview-useful confirmation during this task, update `./codex/documentation/qa.md` using the required original-answer and polished-answer fields.

Do not start writing the repository-root `./README.md`.
