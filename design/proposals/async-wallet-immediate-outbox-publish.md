# Asynchronous Wallet Immediate Outbox Publishing

**Status:** Proposal; not implemented

**Scope:** Wallet's immediate publish after creating a Ledger-command outbox row

## Decision summary

Change `BeforePostLedgerProcessor.postLedger` from a blocking broker-acknowledgment wait to a best-effort asynchronous immediate publish.

Wallet must still commit its business state and the `PENDING` outbox row in the same database transaction before starting Kafka publication. The API result is determined by that committed business transaction, not by the immediate publish result. The scheduled outbox worker remains the durable recovery path.

This proposal changes request latency and failure isolation only. It does not promise exactly-once delivery, remove the outbox, or weaken the requirement that consumers handle duplicate events idempotently.

## Why change it

The current flow commits Wallet state and its outbox, then calls the blocking `IPublisher.publish`, which joins the asynchronous producer future. `DefaultPublisher` may wait up to three seconds for the Kafka result even though a failed or timed-out immediate attempt does not change the successful API response.

That wait adds Kafka latency to the user-facing path without improving durability. It can also let an unexpected post-commit publishing or finalization exception make an already committed Wallet operation appear to have failed.

## Proposed flow

1. Commit Wallet business changes and the Ledger-command outbox row atomically.
2. Complete best-effort cache and idempotency updates as today.
3. Call `publishAsync` for the durable `PENDING` row and attach a completion callback; do not join the future.
4. Return the Wallet result without waiting for Kafka acknowledgment.
5. On successful broker acknowledgment, use a guarded `PENDING -> SENT` database update.
6. On every other outcome, log the failure and leave the row recoverable by the scheduled outbox worker.

Only publication setup runs on the API thread. Database finalization must run on the existing dedicated outbox callback executor, not on a Kafka producer thread or the JVM common pool.

## Failure contract

| Outcome | Required behavior | API result |
|---|---|---|
| Broker acknowledges the event | Callback attempts guarded `PENDING -> SENT` finalization. | Unchanged |
| Publish times out or returns a failed result | Log with event/command identity; leave `PENDING`. | Unchanged |
| Publisher setup throws or returns no future | Suppress after logging; leave `PENDING`. | Unchanged |
| Future completes exceptionally | Suppress after logging; leave `PENDING`. | Unchanged |
| Callback scheduling/execution fails | Suppress after logging; leave `PENDING`. | Unchanged |
| Broker acknowledges, but finalization fails or updates zero rows | Leave the database authoritative; the retry worker may publish a duplicate. | Unchanged |
| Process stops with work in flight | The durable row remains available to the retry worker after restart. | Unchanged |

The finalization gap deliberately preserves duplicate-permitting delivery: Kafka may contain the event even when the row remains `PENDING`. Consumer idempotency is therefore still mandatory.

## Implementation boundary

Prefer extracting the existing immediate-publish policy from the common Kafka listener into a small reusable common-util coordinator. Wallet and listener reply publication then share the same handling for setup exceptions, null/failed futures, callback isolation, and guarded finalization. The coordinator should accept the publisher, outbox persistence boundary, and the qualified outbox callback executor.

Do not implement this by wrapping the blocking `publish` call in `CompletableFuture.runAsync`. That would merely move the three-second wait to another thread, could use the common pool accidentally, and would duplicate failure-handling policy.

The asynchronous callback should operate from stable outbox identifiers and persisted state. It should not depend on later mutation of the request-local `Outbox` object.

## Trade-offs

The change removes producer acknowledgment latency from the API path and makes the existing response semantics honest: a committed Wallet transaction succeeds even when Kafka is temporarily unavailable. In exchange, normal-path publication/finalization becomes less immediate to observe, executor saturation becomes an operational concern, and a retry worker can race a late callback. Guarded status updates and idempotent consumers already provide the required safety for that race.

## Acceptance criteria

- An incomplete producer future does not delay the Wallet API result.
- Publishing starts only after the Wallet transaction and outbox insertion commit.
- Broker acknowledgment is the only result that triggers `SENT` finalization.
- Setup, timeout, completion, callback, rejection, and finalization failures cannot alter the committed API result.
- Every failed or interrupted immediate attempt leaves a row eligible for durable retry.
- Tests cover successful finalization, all suppressed failure classes, process/executor shutdown behavior, and the late-callback-versus-retry race.

For the surrounding current reliability model, see [Kafka Transport, Listener Durability, and Transactional-Outbox Recovery](../infrastructure/kafka-outbox-reliability.md).
