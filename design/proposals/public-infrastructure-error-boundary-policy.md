# Proposal: Public Infrastructure Error Boundary Policy

> Status: accepted policy; implementation pending.
>
> This document defines the intended public error-mapping behavior. The current service boundary mappers have not yet been changed.

## Decision

Wallet and Ledger should map selected temporary infrastructure failures to a service-owned public error with category `UNAVAILABLE`.

The mapping must be selective. An infrastructure error is eligible only when all of the following are true:

1. Normal fallback and server-side retry have been exhausted.
2. The failure is expected to be temporary.
3. The request is known not to have committed, or replay is protected by a complete idempotency contract.
4. Retrying with backoff is an appropriate action for the caller.

Public responses must not expose whether MySQL, Redis, Kafka, or another internal dependency caused the failure. Each service should translate the common error into a service-owned error code whose public category is `UNAVAILABLE`.

## Mapping policy

| Situation | Intended public category | Reason |
|---|---|---|
| Database deadlock or lock timeout with a known rollback | `UNAVAILABLE` | Temporary, and the failed attempt produced no committed effect. |
| Database connection unavailable before the transaction begins | `UNAVAILABLE` | The service cannot currently perform the request, but retry may succeed later. |
| Redis connection/timeout after fallback is exhausted, with no committed effect | `UNAVAILABLE` | The request is temporarily unserviceable and safe replay is known. |
| Kafka failure that prevents request acceptance and leaves no ambiguous durable effect | `UNAVAILABLE` | Temporary inability to accept the request. |
| Serialization, malformed state, configuration, contract, or invariant failure | `INTERNAL` | These indicate an internal defect or invalid internal state rather than temporary availability. |
| Commit, acknowledgment, or timeout failure with an unknown outcome | `INTERNAL` for now | Blind retry may duplicate an effect; no public retry signal should be given until unknown-outcome recovery is explicitly designed. |

If a fallback succeeds—for example, Redis fails but MySQL completes the operation—no infrastructure error should cross the public boundary.

## Meaning of `UNAVAILABLE`

At the public boundary, `UNAVAILABLE` means:

> The running service could not complete this request because of a temporary condition, and retrying according to the API's idempotency and backoff rules is appropriate.

It does not mean that the entire service process or every endpoint is down.

## Retry safety

The internal `retryable` flag is necessary but not sufficient for public `UNAVAILABLE` mapping. The boundary must also consider whether the transaction outcome is known and whether repeating the request is safe.

In particular, an error must not be exposed as retryable merely because it occurred during a database timeout. If the timeout happened during commit and the result is ambiguous, the service must avoid encouraging a blind retry.

## Implementation status

The current Wallet and Ledger boundary mappers generally normalize common database, Redis, cache, Kafka, and outbox failures to service `INTERNAL_ERROR`. Therefore, this policy describes a future mapping change, not current behavior.

Implementation should introduce service-owned unavailable errors and explicit mappings for eligible common failures while retaining the original common error in internal logs and metrics. Code changes, tests, and public retry guidance are outside this documentation-only decision.
