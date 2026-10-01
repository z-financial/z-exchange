# Z-ID-Generator Clock-Skew Risk

## Status

- **Decision:** Accepted risk for the current `z-exchange` integration
- **Integration impact:** Non-blocking
- **Correctness impact:** Duplicate IDs are possible if application clocks exceed the configured skew assumption during worker handover
- **Revisit before:** Treating `z-exchange` as a production-grade financial system, deploying across regions, or relying on IDs as authoritative event time

## Context

`z-id-generator` builds the timestamp portion of an ID from the application JVM's wall clock. Its MySQL lease manager gives one runtime exclusive ownership of a worker ID and delays reuse after lease expiry or release.

The reuse delay is calculated from:

```text
generator time unit + configured maximum relative clock skew + safety margin
```

This protects normal worker handover when the relative clock difference between nodes remains within the configured bound. The generator does not currently compare the JVM clock with MySQL time or persist the maximum timestamp emitted by each worker.

## Failure scenario

Duplicates can occur when a node generates IDs sufficiently far in the future and the same worker is later reused:

1. Node A's clock moves ahead of real/database time by more than the configured skew window.
2. Node A emits IDs containing future timestamp buckets.
3. Node A releases its worker or loses its lease.
4. After the normal cooling period, Node B acquires the same worker with a correct or slower clock.
5. When Node B eventually reaches a timestamp bucket already used by Node A, its sequence restarts from zero and can recreate an earlier `(timestamp, worker, sequence)` tuple.

The lease protocol still prevents simultaneous worker ownership. This risk concerns reuse of a worker after an out-of-bound clock jump.

## Decision

The risk does not block the current `z-exchange` integration because this repository is not intended to provide strict product-level availability and correctness guarantees.

The integration may proceed under these assumptions:

- service hosts use normal operating-system time synchronization;
- all instances use the MySQL-backed generator manager in shared environments;
- the configured clock-skew window reflects the expected maximum difference between hosts;
- ID database constraints remain the final duplicate detector;
- a duplicate-key failure is allowed to fail the affected test/demo operation instead of being automatically repaired;
- local or static worker assignment is not used across multiple service instances.

This is an explicit reduction in the strength of the global uniqueness guarantee. It is acceptable for the current project scope, but it must not be presented as strict Snowflake correctness under arbitrary clock movement.

## Current mitigations

1. Keep operating-system/NTP time synchronization enabled on every host.
2. Use `PrefixType.POSITIVE_SIGNAL` so generated values remain positive signed `long` values.
3. Configure `clockSkewWindowMillis` conservatively for the deployment environment.
4. Keep the existing post-expiration worker cooling period enabled.
5. Retain unique constraints on every business ID column that consumes generated IDs.
6. Log clock-rollback and duplicate-key failures with the service instance and worker ID.
7. Do not fall back to an unleased generator when MySQL lease acquisition or renewal fails.

## Recommended tests for integration

- concurrent ID generation from multiple threads in one JVM;
- concurrent generation from at least two service instances using different leased workers;
- graceful shutdown followed by worker reacquisition;
- lease-renewal failure and local fail-closed behavior;
- an artificial clock rollback that confirms generation fails instead of silently reusing a timestamp;
- duplicate-key handling at wallet and ledger persistence boundaries.

An extreme forward-clock-jump/restart test should remain documented as an expected limitation until a high-water-mark or clock-validation mechanism is implemented.

## Permanent correction, if required later

For stronger production guarantees, implement one of the following:

1. Persist the maximum generated timestamp per worker and prevent reuse until database time is safely beyond it.
2. Compare JVM time with authoritative MySQL time during acquisition and renewal, failing closed when the configured skew bound is exceeded.
3. Derive generation time from a continuously maintained database-time offset and reject unsafe offset changes.

The persisted timestamp high-water mark provides the clearest worker-restart guarantee.

## Revisit triggers

Reopen this issue if any of the following becomes true:

- `z-exchange` is promoted beyond demonstration or learning use;
- wallet or ledger data is expected to satisfy strict audit guarantees;
- deployment becomes multi-region or uses hosts with independent clock sources;
- IDs are used as trusted creation timestamps;
- observed inter-host clock difference approaches the configured limit;
- duplicate IDs or unexplained clock-rollback failures are observed;
- automatic retry after duplicate-key failure becomes a requirement.
