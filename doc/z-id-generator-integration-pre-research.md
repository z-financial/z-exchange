# Z-ID-Generator Integration Pre-Research

## Scope

Reviewed the generator/model/MySQL-manager packages, their supporting provider/runtime code, tests, and the current wallet/ledger ID helpers in `z-exchange`.

## Executive verdict

| Question | Verdict |
|---|---|
| Severe bug? | **Yes — one release-blocking duplicate-ID risk exists.** |
| Several modules/threads in one JVM? | **Yes, if they share one manager/generator instance.** |
| Multiple service instances? | **Not production-ready yet.** The lease design is promising, but has correctness and test gaps. |
| Easy to integrate with `z-exchange`? | **Moderate effort.** Java 21 and the `String` ID columns fit, but packaging, Spring lifecycle, configuration, and dependency alignment are still needed. |

## Findings

### 1. Release blocker: worker reuse can duplicate IDs

`DefaultIdProvider` starts every new runtime with `time = 0` and `sequence = 0`. When a worker is stopped, MySQL release deletes its lease row; the same worker can then be acquired immediately by a fresh runtime. If reacquisition happens in the same time unit, the new runtime can recreate IDs already emitted by the old runtime. This is especially likely with a `SECOND` domain, but is also possible within one millisecond.

Lease expiry also prevents concurrent ownership, but does not protect against a replacement node whose clock is behind the previous owner.

Relevant code:

- `DefaultIdProvider.java`: runtime-only timestamp/sequence state and sequence reset.
- `LeaseDao.java`: release uses `DELETE FROM leases`.
- `MysqlGeneratorRuntimeManager.java`: each acquisition creates a fresh provider.

**Required before adoption:** make worker handover safe. Prefer persisting a per-worker timestamp high-water mark and initializing/waiting above it. At minimum, retain the lease row, enforce a reuse quarantine longer than the time unit plus maximum clock skew, and never immediately recycle a gracefully released worker.

### 2. In-process concurrency is good, with one ownership rule

`DefaultIdProvider.nextId/nextIds` are synchronized; generator lifecycle uses atomics; runtime indexes use read/write locks. The included concurrency suite covers up to 20 generators and 16 threads per generator.

Therefore multiple `z-exchange` modules can safely call one shared generator. Do **not** let each module construct an unleased generator with the same worker ID. Use one singleton manager per service process and share its generator facade.

### 3. Distributed lease design is sound in direction, but incomplete

Good points:

- MySQL uniquely identifies a lease by `(domain, worker)`.
- Acquire/takeover is transactional and renew/release checks owner token plus fencing token.
- Database time controls expiry.
- Local monotonic deadlines fail closed when renewal is lost.
- Renewal is bounded and does not block the ID hot path.

Remaining risks:

- Local deadline is set as `System.nanoTime() + safeTerm` **after** acquire/renew returns. A slow response can move the local deadline too close to, or past, the database expiry. Base it on the monotonic time captured before the DB call, or return the authoritative expiry and subtract a safety margin.
- There are no real-MySQL concurrency/takeover tests. The two MySQL runtime tests use a fake lease manager.
- Only `NONE` clock-rollback and sequence-exhaustion policies exist; generation fails rather than waits. Capacity and clock discipline must be explicit.
- Release deletes the row and resets the fencing token on the next insert. Owner tokens still protect internal mutations, but this is not a monotonic external fencing token.

### 4. Integration fit

The existing wallet and ledger helpers return `String`; a positive Snowflake `long` can be converted with `Long.toString`, so no schema migration is required (`varchar(64)` already fits). Event IDs can keep their current `commandId + suffix` shape while using a Snowflake value as the suffix.

Integration friction:

- `z-id-generator` is a separate `1.0-SNAPSHOT` artifact with no published/internal-repository workflow.
- It has no Spring Boot auto-configuration and creates its own MySQL pool.
- It uses MyBatis-Plus `3.5.14`, while `z-exchange` manages `3.5.4`; dependency compatibility must be verified/aligned.
- It also adds Jackson 3 YAML dependencies alongside `z-exchange`'s Jackson 2 stack.
- `serverInstanceId` is effectively limited to 27 characters because another UUID is appended into a 64-character owner token. The existing `TokenHelper.generateToken(...)` output is too long for this API.
- After sustained MySQL renewal failure, generation intentionally stops within the local safe term. Readiness and error handling must reflect that dependency.

## Rough integration plan

1. **Harden `z-id-generator` first**
   - Fix safe worker reuse and compute local deadlines from DB-call start/authoritative expiry.
   - Add real MySQL tests for simultaneous acquire, expiry takeover, delayed responses, restart/reacquire in the same time unit, clock skew, stale renew/release, and duplicate detection.
   - Publish a versioned artifact; align MyBatis dependencies and document supported Java/Spring combinations.

2. **Provision shared ID infrastructure**
   - Create a dedicated `z_id_generator` schema and manage its tables/seeds as migrations.
   - Use a positive domain. A reasonable starting layout is 41 time bits, 10 worker bits, and 12 sequence bits (validate lifetime and peak rate first).
   - Allocate unique domain-level worker IDs, with enough workers for maximum wallet and ledger replicas plus rollout overlap.

3. **Add a small `z-exchange` adapter**
   - Put generic lifecycle/configuration support in `common-util`; keep wallet/ledger naming facades in their service modules.
   - Create one `MysqlDaoFactory` and one `MysqlGeneratorRuntimeManager` Spring singleton per process, with `close()` as the destroy method.
   - Generate a unique, short (`<= 27` characters) instance ID.
   - Start one leased generator per service process and share it across callers. Convert positive `long` IDs to decimal strings.

4. **Migrate safely**
   - Replace wallet/ledger helper internals without changing their public methods first.
   - Add uniqueness, concurrent-call, multi-instance, restart, lease-loss, and rolling-deployment tests.
   - Roll out one service at a time; expose lease ownership, renew failures, available workers, ID failures, and clock rollback as metrics/readiness signals.

## Validation performed

`mvn test` on `z-id-generator`: **74 tests passed, 0 failed, 1 skipped**. The suite strongly exercises local concurrency, but it does not validate the MySQL protocol against a real database.
