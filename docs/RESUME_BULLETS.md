# JobForge on your resume

Suggested entry, written in the same style as your CampusBalance and Cancio bullets. Pick three
or four. Replace `<live-url>` with your Render URL once deployed (see DEPLOY.md).

**JobForge: AI Workflow Orchestration Engine** | Java 17, Spring Boot, Spring Data JPA, PostgreSQL, Claude API, Micrometer, Docker, GitHub Actions · [GitHub](https://github.com/Manan-Gupta04/jobforge) · [Live demo](<live-url>)

- Built a distributed, fault-tolerant orchestration engine for LLM pipelines: workflows are submitted as a DAG document, step outputs flow into downstream prompts through `{{steps.x.output}}` templates resolved at run time, and independent steps (classify, extract keywords) run in parallel.
- Designed multi-node safe execution on a shared SQL database: jobs are claimed with a lock-free compare-and-swap update (verified by a 12-thread race test over 400 jobs with zero duplicates), run under renewable leases, and are reclaimed by a reaper when a node crashes mid-job.
- Implemented the reliability layer LLM workloads need: exponential backoff with jitter for rate-limited API calls, per-type token-bucket rate limits to stay under provider quotas, per-job timeouts via thread interruption, a dead-letter queue with operator retry, idempotent submission, and transitive cancellation of downstream steps when an upstream step dies.
- Integrated the Claude API through the official Java SDK with a pluggable provider interface and a zero-cost mock for CI and public demos; replaced a polling dispatcher with an event-driven claim loop, lifting single-node throughput from 16 to 472 jobs/s (30x).
- Shipped with 66 unit and integration tests (JUnit 5, Mockito, Awaitility) at 83% line coverage, Prometheus metrics (p50/p95/p99 by job type and outcome), OpenAPI docs, a live dashboard, a Docker Compose two-node cluster, GitHub Actions CI, and a free-tier deployment on Render with Neon PostgreSQL.

## Talking points for interviews

- **What problem does it solve?** Teams wiring LLM calls into products hit the same failures: rate limits, timeouts, flaky steps, and no visibility into multi-step pipelines. JobForge is the execution layer under those pipelines: it guarantees each step runs, retries sensibly, respects quotas, and surfaces what happened per attempt and per node.
- **Why CAS over `SELECT … FOR UPDATE SKIP LOCKED`?** Portability, one short transaction per claim, no locks held while scanning. Trade-off: under extreme contention losers waste a round trip, mitigated by over-fetching 3× the free slots.
- **At-least-once vs exactly-once.** Exactly-once needs handler cooperation (idempotency keys on the side effect). The engine guarantees no concurrent double-claim and no lost work; the handler contract documents idempotency.
- **Why the lease lives on the job row.** Claim, heartbeat, reap and complete are all single-row conditional updates, so there is no cross-table consistency problem and no distributed lock service.
- **How do templates stay safe?** They are resolved right before execution from the upstream step's persisted result, and referencing a step that is not a declared dependency fails that step with a clear error instead of reading a partial result.
- **Why rate-limit at claim time instead of inside the handler?** A throttled job is simply not claimed and stays queued, so the limit never loses work and never holds a worker thread idle. The limit is per node, which is the honest trade-off for not needing a shared counter; the README says so.
- **Where is the bottleneck now?** One UPDATE transaction per claim. Next step would be a batched claim in one transaction, or `SKIP LOCKED` on PostgreSQL, both measurable with `scripts/benchmark.sh`.
- **What happens on shutdown?** Graceful: the dispatcher stops claiming, the pool drains for 10 s, then in-flight jobs are interrupted and go back to the queue as a retry.
