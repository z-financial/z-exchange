# Decision: Unique Ledger Reply Event References

> Status: accepted for the event/outbox identity evolution.
>
> Scope: Ledger reply-outbox identity and Wallet handling of repeated Ledger replies.

## Decision

Treat every newly persisted Ledger reply as a distinct event instance:

- Allocate one generated `internal_id` for the reply outbox row.
- Encode that value as an opaque `LER` `event_ref` and persist both together.
- Reuse the stored `event_ref` for every publication attempt of that row.
- Preserve `command_ref` as the Wallet transaction correlation key and Kafka partition key.
- Allow repeated processing of one request to produce multiple reply rows with different `event_ref` values.
- Let Wallet's guarded transaction state prevent repeated successful replies from applying effects twice.

The reference belongs to the durable reply event, not to an individual Kafka send attempt. It is independent of the
request event reference and reply outcome. “Independent” does not require a second generated value: the outbox
`internal_id` and opaque `event_ref` are the storage and boundary representations of one event identity.

## Motivation

The previous design derived a Ledger reply reference from the request event and outcome. An initial successful
result and an idempotent replay could then receive the same reference while carrying different diagnostic payload
details. The outbox correctly treated reuse of one reference with different bytes as a conflict, but the identity
model had incorrectly assigned one event identity to two separately constructed observations.

Giving each persisted reply its own event reference removes that false conflict. Wallet correlates replies through
`command_ref` and converges through transaction state; it does not infer business identity from `event_ref`.

## Identity and idempotency boundaries

| Concern | Owner and rule |
|---|---|
| Reply event identity | Ledger creates `LER-<ENC_INTERNAL_ID>-<CHK>` from the new reply outbox `internal_id`. |
| Outbox publication retry | Reuses the `event_ref` and payload stored on that row. |
| Accidental reference reuse | Existing full-payload `sameIntent` verification remains; a mismatch is a conflict. |
| Repeated request processing | May create multiple reply rows with different event references. |
| Transaction correlation | `command_ref` continues to identify the Wallet transaction. |
| Accounting idempotency | Ledger's command idempotency prevents duplicate accounting effects. |
| Wallet effect idempotency | Wallet's guarded database transition applies a successful outcome at most once. |

## Reply lifecycle

| Observed sequence for one `command_ref` | Required Wallet behavior |
|---|---|
| Failure only | Does not advance the transaction to success. |
| Failure, then success | The later success may complete the transaction. |
| Success, then repeated success | The committed transition wins; later successes have no duplicate effect. |
| Success, then late failure | Does not reverse the completed transaction. |
| Concurrent successes | Database serialization or guarded updates prevent duplicate balance or reservation effects. |

## Explicitly outside this phase

Valid negative Ledger replies currently follow the Wallet listener's failure/DLT path. Separating valid business
failure observations from malformed, transient, or permanently unprocessable messages is a separate story. This
identity change neither defines terminal compensation nor introduces inbox processing.

The planned durable inbox/outbox design may later ensure that one inbox item materializes one logical reply row.
That does not change the rule here: every persisted outbox event owns one stable `event_ref` that survives all of
its delivery attempts. See [Durable Inbox/Outbox Processing Refactor](inbox-outbox-processing.md).
