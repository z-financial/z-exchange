# End-to-End Transaction Flow

**Scope:** business transaction progression across Wallet, Kafka/outbox, and Ledger  
**Evidence state:** synthesized from verified transaction and reliability designs on 2026-09-09

## Flow in one minute

![End-to-end Wallet and Ledger transaction flow](../assets/end-to-end-transaction-flow.svg)

A request first becomes durable Wallet business state. Reserve and pure release end there. Transfers and reservation consumption return a durable `PENDING` Wallet transaction, cross the asynchronous accounting boundary, and finish only when a successful Ledger reply drives the second Wallet database transaction.

The client-facing transaction RPC therefore distinguishes **acceptance** from **settlement**. No Wallet or Ledger database lock is held while Kafka or the other service is processing.

## Step 1 — Validate, deduplicate, and normalize

Wallet validates the operation and lines, creates a stable request identity, and checks layered idempotency. Redis coordinates a live attempt; MySQL remains the durable duplicate-effect boundary.

The request is normalized into actions:

| Action | Business meaning |
|---|---|
| `RESERVE` | Move spendable funds into the held bucket |
| `RELEASE` | Return unconsumed held funds to available |
| `CONSUME` | Associate pending/final settlement with a reservation |
| `TRANSFER_OUT` | Remove Wallet-held value after accounting success |
| `TRANSFER_IN` | Credit Wallet-held value after accounting success |

Only transfer actions become Ledger debit/credit entries. Reserve, consume, and release remain Wallet business state.

## Step 2 — Commit Wallet phase 1

Wallet locks the affected current balances and reservations, applies the phase-1 transitions, and atomically persists:

- changed balance snapshots and reservations;
- the Wallet transaction and actions;
- a Ledger command outbox when accounting is required.

For reserve and pure release, Wallet sets the transaction to `COMPLETED` and returns the committed local result. For Ledger-backed work, it leaves the transaction `PENDING`. Outgoing funds are already reserved or pending settlement, so they are not available for another normal spend while the remote result is unknown.

The command carries the Wallet transaction ID as Ledger `referenceId`, correlation/command identity, and Kafka partition key. This stable identity connects retries to the same intended accounting effect.

## Step 3 — Deliver and post to Ledger

Wallet may publish immediately after commit, while the durable outbox remains the recovery source. A scheduled dispatcher retries pending rows. Broker acknowledgment and outbox `SENT` finalization are separate, so duplicate command delivery is a normal possibility.

Ledger coordinates duplicate attempts through Redis and checks MySQL by the Wallet transaction reference. For a new request it:

1. resolves submitted account references;
2. validates at least one debit and credit and equality of debit/credit totals per asset;
3. atomically inserts the Ledger transaction header and complete entry batch; and
4. creates or verifies a durable result reply before the command is acknowledged.

MySQL uniqueness permits at most one committed Ledger header for the Wallet transaction reference. Redis improves hot-path coordination but is not the durable accounting authority.

## Step 4 — Apply the Ledger reply

The Ledger reply outbox may publish through Kafka more than once while the row remains retry-eligible. A successful reply contains the Wallet transaction reference and Ledger transaction ID. Wallet reloads the transaction and affected financial rows, then atomically:

- moves reservation amounts from `pendingSettle` to `consumed`;
- subtracts outgoing value from reserved balances;
- adds incoming value to available balances; and
- changes `PENDING → COMPLETED`.

A later sequential duplicate sees the already-final state and performs no second financial effect. The listener acknowledges only after the finalization handler returns from its durable work.

## Operation timing

| Flow | Before response | After response |
|---|---|---|
| reserve | available → reserved; reservation created; transaction completed | no Ledger work |
| pure release | remaining → released; reserved → available; transaction completed | no Ledger work |
| consume / earmark | remaining → pending settle; transaction and command committed | Ledger post, then pending → consumed and reserved decreases |
| atomic transfer | outgoing available → reserved; transaction and command committed | Ledger post, then outgoing reserved decreases and incoming available increases |

## Identity and atomicity map

| Boundary | Stable identity | Atomic unit |
|---|---|---|
| Client → Wallet | `(initiator, idempotency_key)` plus live request hash | Wallet phase-1 transaction |
| Wallet → Ledger | Wallet transaction ID / Ledger reference | Ledger header and entries |
| Ledger → Wallet | request/reply event and Wallet transaction reference | Wallet phase-2 transaction |
| Outbox delivery | unique event ID | event persistence/finalization, separate from broker send |

The databases prevent repeated durable effects for their stable identities, but neither service currently persists the canonical request hash. After Redis expiry, durable same-key/same-payload equivalence is therefore narrower than the intended idempotency contract.

## Completion and recovery boundary

`PENDING` is a meaningful business state, not merely a transport status. It can represent accepted work awaiting Ledger or Wallet finalization. Outbox retries handle ordinary publication failure and duplicate delivery. A client can retry an ambiguous Wallet request with the same identity; Wallet's database can recover the committed transaction ID.

If Ledger committed but no usable reply converges, the intended repair is a Wallet-owned scheduled re-drive of the same logical Ledger command. That job is not implemented. A valid Ledger rejection also needs a future compensation transition; current handling leaves the phase-1 hold pending. Full scenario classification is in [Wallet–Ledger Consistency and Reliability](../consistency/wallet-ledger-consistency.md).

## Deep dives

- Wallet phase mechanics → [Wallet Transaction Lifecycle](../wallet/transaction-lifecycle-and-settlement.md)
- balances and row concurrency → [Wallet Balance and Concurrency](../wallet/balance-and-concurrency-correctness.md)
- reservation state → [Wallet Reservation Lifecycle](../wallet/reservation-lifecycle.md)
- Ledger append rules → [Ledger Posting and Accounting Correctness](../ledger/posting-and-accounting-correctness.md)
- Kafka/outbox delivery → [Kafka and Outbox Reliability](../infrastructure/kafka-outbox-reliability.md)
