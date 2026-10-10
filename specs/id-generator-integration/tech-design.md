# Managed ID Generator Runtime — Technical Design

Related documents: [SPEC](./spec.md) | [Implementation tasks](./tasks.md)

## 1. Purpose

This design prepares Wallet and Ledger to use one Spring-managed `z-id-generator`
runtime per service process. Both services share the generator database but use
separate domains, so generated values are unique within a service, not globally
across Wallet and Ledger.

The immutable generator artifact will be selected before Task 1 is completed. During
development, integration may use a locally built snapshot from a recorded generator
commit.

## 2. Change Scope

### Included

- Create and seed the shared generator database through Docker initialization.
- Create a least-privilege generator runtime database user.
- Add generator database variables to infrastructure, Wallet, and Ledger deployment
  configuration.
- Complete the `common-util` Spring adapter around `z-id-generator`.
- Use a dedicated bounded Hikari pool for generator metadata and leases.
- Configure startup, renewal, shutdown, readiness, and lease-loss behavior.
- Configure Wallet and Ledger with separate domains and one `internal-id` generator
  per domain.
- Add focused integration tests for provisioning and runtime lifecycle.

### Excluded

- Migrating Wallet, Ledger, or event allocation call sites; those remain Tasks 2–4.
- Changing database ID columns, protobuf fields, API shapes, or message formats.
- Introducing `internal_id` and typed `*_ref` fields.
- Providing globally unique IDs across services.
- Seeding worker or lease rows.
- Adding an application-level fallback generator or automatic in-process lease
  reacquisition.

## 3. Database Provisioning

Use one MySQL database:

```text
z_id_generator
```

Docker initialization creates the schema from the authoritative `z-id-generator`
MySQL definition. It contains:

```text
domains
counters
generator_configs
workers
leases
```

Provision only the following immutable definitions:

| Service | Domain | Generator |
| --- | --- | --- |
| Wallet | `z-exchange-wallet` | `internal-id` |
| Ledger | `z-exchange-ledger` | `internal-id` |

Both domains use:

```text
prefix_type:            POSITIVE_SIGNAL
time_unit:              MILLISECOND
time_bit:               41
worker_bit:             10
sequence_bit:           12
epoch_start:            2026-10-01T00:00:00Z
epoch_start_ms:         1790812800000
maximum_retry:          0
id_run_out_policy:      NONE
clock_rollback_policy:  NONE
```

Each domain receives its required worker-allocation counter initialized to zero.
Workers and leases are created dynamically by the runtime. A newly registered worker
is committed before lease acquisition; if startup stops between those operations, the
valid unleased worker remains available for a later instance.

The Docker initialization order is:

```text
001-ledger-service.sql
002-wallet-service.sql
003-id-generator.sql
004-grant-service-users.sh
```

The scripts run only for a new MySQL data directory. Development environments must
recreate the disposable MySQL volume when validating a changed initialization script.

## 4. Runtime Database Access

Wallet and Ledger share the following runtime account in this development and study
environment:

```text
database: z_id_generator
username: zid_runtime
password: ID_GENERATOR_DB_PASSWORD
```

Grant only:

```text
domains:           SELECT
generator_configs: SELECT
counters:          SELECT, UPDATE
workers:           SELECT, INSERT
leases:            SELECT, INSERT, UPDATE
```

The runtime account receives no DDL, delete, or grant permissions. Database creation
and immutable definition seeding remain deployment responsibilities.

## 5. Thin Spring Adapter

`common-util` provides infrastructure integration without service-domain policy. The
adapter:

1. Creates a dedicated bounded Hikari pool.
2. Creates the MySQL DAO factory and runtime manager.
3. Resolves the configured `internal-id` generator.
4. Starts one generator instance for the process.
5. Exposes a small application-facing `nextId()` operation.
6. Maps library failures into a dedicated `IdGeneratorException` with stable
   project error codes while preserving the original exception as its cause.
7. Attaches non-secret service, domain, generator, and instance context to
   operational logs.
8. Closes the generator runtime before closing its pool.

The adapter does not provision definitions, choose worker IDs, decode IDs for business
logic, or provide a fallback. Wallet and Ledger supply their domain name through
service configuration.

## 6. Lifecycle and Failure Policy

Use explicit runtime settings:

```text
lease term:             10 seconds
local safe term:         8 seconds
renew interval:          5 seconds
cleaner interval:        1 second
clock-skew window:       1 second
reuse safety margin:   100 milliseconds
```

Missing configuration, unavailable MySQL, missing registration, worker exhaustion,
or initial lease failure fails application startup. A transient renewal failure does
not immediately stop generation, but it never extends the local safe deadline.

When ownership is lost or the local safe deadline expires, the adapter shall:

1. Stop accepting ID allocations.
2. Mark readiness `REFUSING_TRAFFIC`.
3. Close the Spring application so the service process exits.
4. Rely on Docker's restart policy to start a new process and acquire a safe worker.

Docker does not restart a container merely because it becomes unhealthy, so confirmed
ownership loss must terminate the application rather than only changing health state.

TODO: In a future phase, replace process restart with safe in-process recovery: keep
the service unready, acquire a new worker lease, and resume generation only after the
new ownership is confirmed.

## 7. Verification

Task 1 verifies:

- a fresh Docker environment creates the schema, definitions, counters, and grants;
- Wallet and Ledger resolve their separate domains and the same generator name;
- concurrent service processes obtain different workers within one domain;
- generation after startup performs no database call per ID;
- shutdown releases runtime resources and closes the dedicated pool;
- startup failures are visible and fail closed; and
- confirmed lease loss stops generation and causes process restart.

The immutable `z-id-generator` release remains a Task 1 completion gate, not a
prerequisite for beginning the implementation.
