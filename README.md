# JobForge

**A distributed, fault-tolerant job scheduler and workflow engine in Java 17 / Spring Boot 3.**

Any number of JobForge nodes share one SQL database and cooperatively drain a queue of jobs.
Each node claims due jobs with a lock-free compare-and-swap, runs them in a worker pool under a
renewable lease, retries failures with exponential backoff and jitter, dead-letters jobs that
exhaust their attempts, and releases downstream jobs in a dependency graph as upstream jobs
succeed. If a node dies mid-job, another node's reaper reclaims the work.

![JobForge dashboard](docs/dashboard.jpg)

| | |
|---|---|
| **Stack** | Java 17, Spring Boot 3.5, Spring Data JPA, H2 / PostgreSQL, Micrometer + Prometheus, springdoc OpenAPI, JUnit 5, Mockito, Awaitility, JaCoCo, Docker |
| **Tests** | 46 tests (unit + integration), 85 % line coverage, including a 12-thread claim race test proving no job is ever claimed twice |
| **Throughput** | 472 jobs/s on one node with 8 workers against a file-backed H2 database (5,000 jobs drained in 10.6 s on a laptop) |

---

## Features

- **At-least-once execution with leases.** A claimed job carries `lease_owner` / `lease_until`. Workers heartbeat to extend the lease; a reaper on every node hands back any job whose lease expired, so a crashed or hung node never loses work.
- **Lock-free, multi-node safe claiming.** Claims are a single conditional `UPDATE … WHERE id = ? AND version = ? AND status = 'PENDING'`. The database serialises row writes, so exactly one of N racing nodes wins. No advisory locks, no `SKIP LOCKED`, works on H2, PostgreSQL and MySQL.
- **Retries with exponential backoff and jitter**, then a **dead-letter queue** with one-click operator retry.
- **Job dependencies (DAG).** A job may depend on any number of other jobs; it stays `WAITING` until all of them `SUCCEEDED`. If an upstream job dies or is cancelled, the whole downstream chain is cancelled transitively.
- **Priorities, delayed jobs and cron schedules** (6-field Spring cron). Each cron run schedules the next occurrence atomically in the same transaction that completes the current one.
- **Per-job timeouts** enforced by interrupting the handler thread.
- **Idempotent submission.** Resubmitting with the same `idempotencyKey` returns the original job, even under concurrent submits (the unique constraint is the arbiter, and the losing writer recovers).
- **Webhooks.** A JSON summary is POSTed to `callbackUrl` after the completing transaction commits, off the worker thread.
- **Pluggable handlers.** Implement one interface, register it as a bean, and the type is routable, validated at submission and listed in the API.
- **Operations built in.** Live dashboard, OpenAPI/Swagger UI, Prometheus metrics (job duration percentiles per type and outcome, retries, reclaimed leases, worker utilisation), per-attempt execution history with the node that ran it, pause/resume a node for draining, cluster membership via heartbeats.

## Architecture

```
            POST /api/v1/jobs                      ┌──────────────────────────────┐
  client ─────────────────────►  JobService ─────► │  jobs table (any SQL DB)     │
            idempotency, validation,               │  status · run_at · priority  │
            dependency checks                      │  lease_owner · lease_until   │
                                                   │  version (CAS) · attempts    │
                                                   └──────────────┬───────────────┘
                                                                  │
          ┌───────────────────────── node 1 ───────────┐          │          ┌── node 2 ──┐
          │  JobDispatcher (event-driven loop)         │◄─────────┼─────────►│   same     │
          │   free slots = workers − inFlight          │  claim   │          │   code     │
          │   claimBatch(free) ── CAS UPDATE per job   │          │          └────────────┘
          │        │                                   │          │
          │        ▼  worker pool (N threads)          │          │
          │  JobExecutor ── handler on helper thread   │          │
          │   ├─ heartbeat every lease/3 ──────────────┼──────────┤  extend lease_until
          │   ├─ timeout → interrupt                   │          │
          │   └─ outcome → JobStateService (1 tx):     │          │
          │        succeed / retry+backoff / dead      │          │
          │        release dependents · next cron run  │          │
          │        execution history · events          │          │
          │  LeaseReaper: RUNNING ∧ lease_until < now  │          │
          │        → back to PENDING (or DEAD)         │──────────┘
          └────────────────────────────────────────────┘
                 │ events
                 ├─ MetricsListener  → Micrometer / Prometheus
                 └─ WebhookNotifier  → POST callbackUrl (after commit)
```

**Job lifecycle**

```
 WAITING ──(all deps succeed)──► PENDING ──(claimed)──► RUNNING ──► SUCCEEDED
    │                               ▲                      │
    │                               └──(retry w/ backoff)──┤
    │                                                      ▼
    └──(a dep dies)──► CANCELLED                          DEAD (attempts exhausted)
```

### Design decisions worth asking me about

- **Why compare-and-swap instead of `SELECT … FOR UPDATE SKIP LOCKED`?** Portability and simplicity. The CAS claim is one short transaction per job, holds no lock while scanning candidates, and behaves identically on every SQL database. The over-fetch (3× free slots) means losing a few races still fills a node's slots in one pass.
- **Why is the dispatcher event-driven?** A pure polling loop caps throughput at `workers / pollInterval` (8 workers at 500 ms = 16 jobs/s). JobForge's loop wakes the instant a worker finishes and claims again, so throughput is bounded by the database, not the timer. Measured: 16 → 472 jobs/s on the same hardware.
- **Why leases instead of a heartbeat table?** The lease lives on the job row, so claim, heartbeat, reap and complete are all single-row conditional updates and need no cross-table consistency.
- **Why at-least-once?** Exactly-once is impossible without cooperation from the handler. JobForge guarantees a job is never *claimed* twice concurrently and never *lost*; handlers are documented to be idempotent for the crash-after-side-effect case.
- **Stale results are rejected.** If a reaped job finishes late on its original node, the completion transaction sees the job is no longer `RUNNING` and discards the result, so the retry on another node is authoritative.

## Quick start

Requires Java 17+ and Maven. No database setup: the default profile uses file-backed H2.

```bash
mvn spring-boot:run
```

Then open:

| URL | What |
|---|---|
| http://localhost:8080 | Live dashboard: queue depth, worker utilisation, cluster nodes, recent jobs, demo buttons |
| http://localhost:8080/swagger-ui.html | Interactive API docs |
| http://localhost:8080/actuator/prometheus | Metrics |
| http://localhost:8080/h2 | H2 console (JDBC URL `jdbc:h2:file:./data/jobforge`, user `sa`) |

### Two-node cluster with PostgreSQL

```bash
docker compose up --build
```

Node 1 is on http://localhost:8081 and node 2 on http://localhost:8082, sharing one PostgreSQL.
Submit to either; both drain the queue. Kill one (`docker compose kill jobforge-2`) while jobs are
running and watch the other node's reaper reclaim its expired leases.

### Benchmark

```bash
./scripts/benchmark.sh 5000          # or .\scripts\benchmark.ps1 -Count 5000 on Windows
```

```
Queued 5000 jobs in 1925 ms
Drained 5000 jobs in 10.6s  ->  472 jobs/s
```

(single node, 8 workers, H2 file database, Windows laptop; `echo` handler, so this measures the engine's claim → execute → commit overhead, not handler work.)

## API

Submit a job:

```bash
curl -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' -d '{
  "type": "flaky",
  "payload": { "failTimes": 2 },
  "priority": 7,
  "maxAttempts": 5,
  "timeoutMs": 30000,
  "idempotencyKey": "order-42-notify",
  "callbackUrl": "https://example.com/hooks/jobforge"
}'
```

A dependency chain (C runs after B, B after A):

```bash
A=$(curl -s -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' -d '{"type":"sleep","payload":{"ms":1500}}' | jq -r .id)
B=$(curl -s -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' -d "{\"type\":\"sleep\",\"payload\":{\"ms\":1500},\"dependsOn\":[\"$A\"]}" | jq -r .id)
curl -s -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' -d "{\"type\":\"echo\",\"dependsOn\":[\"$B\"]}"
```

A recurring job every 5 minutes:

```bash
curl -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' \
     -d '{"type":"echo","cron":"0 */5 * * * *","payload":{"report":"sales"}}'
```

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/jobs` | Submit (201 new, 200 if idempotency key already seen) |
| `POST` | `/api/v1/jobs/bulk` | Submit N identical jobs in one transaction |
| `GET` | `/api/v1/jobs?status=&type=&page=&size=` | List, newest first |
| `GET` | `/api/v1/jobs/{id}` | Job detail incl. lease, result, error |
| `GET` | `/api/v1/jobs/{id}/attempts` | Execution history: attempt, node, outcome, duration |
| `POST` | `/api/v1/jobs/{id}/cancel` | Cancel (dependents cancelled too; a running job is interrupted on its next heartbeat) |
| `POST` | `/api/v1/jobs/{id}/retry` | Re-queue a `DEAD`/`CANCELLED` job with a fresh attempt budget |
| `GET` | `/api/v1/stats` | Queue depth by status, worker utilisation, throughput, live nodes |
| `GET` | `/api/v1/handlers` | Registered job types |
| `POST` | `/api/v1/dispatcher/pause` · `/resume` | Drain this node without stopping it |

Errors are RFC 7807 problem details (`400` validation with per-field messages, `404`, `422` for engine rules such as unknown type, bad cron, unknown dependency, or cancelling a finished job).

## Writing a handler

```java
@Component
public class SendEmailHandler implements JobHandler {
    @Override public String type() { return "send-email"; }

    @Override
    public JobResult execute(JobContext ctx) throws Exception {
        String to = ctx.getString("to", null);
        if (to == null) throw new IllegalArgumentException("payload.to is required");
        mailer.send(to, ctx.getString("subject", ""), ctx.getString("body", ""));
        return JobResult.of(Map.of("sentTo", to, "attempt", ctx.attempt()));
    }
}
```

Throw to fail (the job is retried with backoff until `maxAttempts`); honour interruption, which is
how timeouts and cancellations reach you. `ctx.heartbeat()` is available for very long jobs but the
engine already heartbeats in the background.

Built-in handlers for demos: `echo`, `sleep` (I/O-bound), `primes` (CPU-bound), `flaky` (fails N times then succeeds), `fail` (always fails), `http-get`.

## Configuration

All settings are environment-overridable.

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `jobforge.workers` | `JOBFORGE_WORKERS` | 8 | Concurrent jobs per node |
| `jobforge.poll-interval-ms` | `JOBFORGE_POLL_INTERVAL_MS` | 500 | Idle sleep when nothing is due |
| `jobforge.lease-seconds` | `JOBFORGE_LEASE_SECONDS` | 30 | Lease length; heartbeat every third of it |
| `jobforge.reaper-interval-ms` | `JOBFORGE_REAPER_INTERVAL_MS` | 5000 | How often expired leases are reclaimed |
| `jobforge.retry-base-delay-ms` | `JOBFORGE_RETRY_BASE_DELAY_MS` | 1000 | Backoff base: `base · 2^(attempt−1)` ± 20 % |
| `jobforge.retry-max-delay-ms` | `JOBFORGE_RETRY_MAX_DELAY_MS` | 300000 | Backoff cap |
| `jobforge.default-timeout-ms` | `JOBFORGE_DEFAULT_TIMEOUT_MS` | 60000 | Per-attempt timeout if the job does not set one |
| `jobforge.default-max-attempts` | `JOBFORGE_DEFAULT_MAX_ATTEMPTS` | 3 | Attempts if the job does not set one |
| `jobforge.node-id` | `JOBFORGE_NODE_ID` | hostname + random | Stable node name for clusters |
| profile `postgres` | `SPRING_PROFILES_ACTIVE=postgres` | | Use PostgreSQL (`JOBFORGE_DB_URL`, `JOBFORGE_DB_USER`, `JOBFORGE_DB_PASSWORD`) |

## Testing

```bash
mvn test            # 46 tests, JaCoCo report in target/site/jacoco/index.html
```

| Suite | What it proves |
|---|---|
| `ExponentialBackoffPolicyTest` | Doubling, cap, jitter bounds, overflow safety |
| `JobHandlerRegistryTest` | Routing, duplicate / blank type rejection |
| `JobStateServiceTest` (Mockito) | Every state transition: success releases dependents only when *all* deps succeeded; retry uses backoff; last failure dead-letters and cancels the chain; stale results from reaped workers are discarded; cron spawns the next run; reaper leaves renewed leases alone |
| `JobServiceTest` (Mockito) | Validation (unknown type, bad cron, missing dependency), defaults, idempotency including losing the insert race |
| `JobLifecycleIT` | End-to-end through the real dispatcher, pool, reaper and H2: completion, retry with backoff, dead-letter, operator resurrect, timeout, DAG ordering, transitive cancellation, delayed jobs, priority ordering, cron rescheduling, cancel-before-run |
| `ConcurrencyIT` | 12 threads racing to claim 400 jobs claim each exactly once; a burst of 500 jobs executes exactly once each |
| `JobApiIT` (MockMvc) | HTTP contract: 201/200 idempotency, problem details, validation fields, cancel/retry round trip, filters, bulk |

CI (`.github/workflows/ci.yml`) runs the suite and builds the Docker image on every push and pull request.

## Project layout

```
src/main/java/com/manangupta/jobforge
├── api/            REST controllers, DTOs, RFC 7807 error mapping
├── config/         SchedulerProperties, executors, node identity, OpenAPI
├── domain/         Job, JobExecution, ClusterNode entities; JobStatus state machine
├── handler/        JobHandler SPI, registry, built-in handlers
├── repository/     Spring Data repositories incl. the CAS claim and lease queries
├── retry/          BackoffPolicy, ExponentialBackoffPolicy
└── service/        JobService, JobClaimService, JobDispatcher, JobExecutor,
                    JobStateService, LeaseReaper, ClusterRegistry, events, webhooks, metrics
src/main/resources/static/index.html   dashboard (vanilla JS, polls /api/v1/stats)
```

## Roadmap

- Rate limiting per job type and per-tenant fair scheduling
- Workflow definitions submitted as a single DAG document
- Partitioned queues with consistent hashing for very large clusters
- Native `SKIP LOCKED` claim strategy as an optional PostgreSQL optimisation

## Author

Manan Gupta · [github.com/Manan-Gupta04](https://github.com/Manan-Gupta04) · [linkedin.com/in/manangupta7899](https://linkedin.com/in/manangupta7899)
