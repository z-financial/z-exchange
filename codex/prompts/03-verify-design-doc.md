# Codex Task — Verify One Detailed Design Against the Implementation

> Reusable prompt. Prefer a fresh Codex task/session from the drafting task when practical.

## Task parameters

```text
DESIGN_ID: <D-xxx>
TARGET_DOC: <path under ./design>
```

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. `TARGET_DOC`
4. relevant dependency docs only when needed

## Goal

Perform an **adversarial verification pass** on the selected design document.

Confirm that `TARGET_DOC` is under `./design`, that every documentation/asset link remains inside `./design`, and that the document does not cite or direct readers to external documentation. Repository source/config/schema/tests may still be inspected as verification evidence.

Your job is not to make the document sound better. Your primary job is to find claims that are:

- wrong
- stale
- too strong
- unsupported
- ambiguous
- inconsistent with current source/config/tests/schema
- inconsistent with another canonical design doc

Then correct the document.

## Verification method

For each important claim or guarantee, locate implementation evidence.

Pay special attention to:

- transaction boundaries
- locking order/behavior
- conditional updates
- database uniqueness constraints
- idempotency boundaries
- Redis behavior
- Kafka producer/consumer semantics
- outbox state transitions
- retry behavior
- exception/error paths
- crash windows
- timeout/unknown-result behavior
- partial-failure behavior
- cache invalidation/refresh correctness
- actual use of common-util abstractions
- performance numbers and environment
- reconciliation claims

Search for negative evidence too: code paths that contradict the happy-path narrative.

## Classification

Important claims about reliability/correctness should be classifiable as one of:

```text
CURRENTLY GUARANTEED
CURRENTLY MITIGATED / BEST-EFFORT
NOT IMPLEMENTED / FUTURE WORK
UNKNOWN / NEED CONFIRMATION
```

If the document implies a guarantee but the implementation only mitigates the risk, rewrite it.

## Handling uncertainty

If the repository cannot establish an important fact:

- add `TODO: NEED CONFIRMATION` in the correct section;
- explain exactly what is unknown;
- do not guess.

If the uncertainty materially invalidates the document's core story, set manifest status `BLOCKED` instead of `VERIFIED`.

## End-of-task actions

1. Correct `TARGET_DOC` in place.
2. Remove claims that cannot be supported and are not useful as explicit TODOs.
3. Update the manifest evidence and uncertainty records.
4. Record what was verified against current implementation.
5. If sufficiently validated, set status to `VERIFIED` and record the verification date/context.
6. Otherwise set `BLOCKED` or leave `DRAFTED` with a clear reason.
7. Identify any dependent docs now requiring `NEEDS_REVERIFY` because this verification changed a canonical concept.
8. Reject or replace links/references from `TARGET_DOC` to documentation outside `./design`; incorporate validated information into the appropriate internal canonical owner instead.
9. Record any interview-useful confirmation resolved with the user in `./codex/documentation/qa.md`; distinguish confirmed intent from implementation evidence.
