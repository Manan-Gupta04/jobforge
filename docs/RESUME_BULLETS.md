# JobForge on your resume

Suggested entry, written in the same style as your CampusBalance and Cancio bullets. Pick three.

**JobForge** | Java 17, Spring Boot, Spring Data JPA, PostgreSQL/H2, Micrometer, Docker, GitHub Actions

- Built a distributed, fault-tolerant job scheduler and workflow engine where N nodes share one SQL database; jobs are claimed with a lock-free compare-and-swap update so no job is ever run twice concurrently, verified by a 12-thread race test over 400 jobs.
- Designed lease-based at-least-once execution: workers heartbeat to extend a per-job lease, a reaper on every node reclaims work from crashed nodes, and stale results from reaped workers are rejected; verified by hard-killing a node mid-job and watching a restarted node recover it.
- Implemented retries with exponential backoff and jitter, a dead-letter queue with operator retry, DAG job dependencies with transitive cancellation, cron rescheduling, per-job timeouts via thread interruption, and idempotent submission that survives concurrent duplicate requests.
- Replaced a polling dispatcher with an event-driven claim loop, raising single-node throughput from 16 to 472 jobs/s (30x) on the same hardware; exposed Prometheus metrics (p50/p95/p99 job duration by type and outcome), an OpenAPI spec and a live dashboard.
- 46 unit and integration tests (JUnit 5, Mockito, Awaitility) at 85% line coverage, Docker Compose two-node cluster, CI pipeline on GitHub Actions.

## Talking points for interviews

- **Why CAS over `SELECT … FOR UPDATE SKIP LOCKED`?** Portability, one short transaction per claim, no locks held while scanning. Trade-off: under extreme contention losers waste a round trip, mitigated by over-fetching 3× the free slots.
- **At-least-once vs exactly-once.** Exactly-once needs handler cooperation (idempotency keys on the side effect). The engine guarantees no concurrent double-claim and no lost work; the handler contract documents idempotency.
- **Why the lease lives on the job row.** Claim, heartbeat, reap and complete are all single-row conditional updates, so there is no cross-table consistency problem and no distributed lock service.
- **Where is the bottleneck now?** One UPDATE transaction per claim. Next step would be a batched claim in one transaction, or `SKIP LOCKED` on PostgreSQL, both measurable with `scripts/benchmark.sh`.
- **How do dependencies work without a graph table?** `job_dependencies` is an element collection; on each success the engine finds dependents and counts how many of their dependencies have succeeded. Cancellation walks the graph recursively.
- **What happens on shutdown?** Graceful: the dispatcher stops claiming, the pool drains for 10 s, then in-flight jobs are interrupted and go back to the queue as a retry.
