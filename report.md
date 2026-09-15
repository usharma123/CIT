# Observability implementation and findings

## Summary

This project compares three ways to support incident diagnosis in the Mocknet Java service. Each approach processes the same kind of trade workload through a PostgreSQL-backed queue. Grafana provides service, queue and operation investigation views.

**Approach A is the recommended basis for L3 support.** It combines business identifiers, durable queue history, runtime metrics, dependency spans and correlated logs. Approach B requires the fewest application changes but cannot diagnose business or queue failures on its own. Approach C is useful when agents are prohibited and sufficiently detailed logs and journals are available, but it requires a custom collection and reconstruction system.

C now has the same native Grafana waterfall renderer as A. Equal visual quality does not make the underlying evidence equivalent: C cannot reveal an SQL call or dependency that its source records never captured.

These are local implementation and incident exercises. They do not certify production capacity, full distributed coverage, external settlement, or a general performance winner.

## Service and workload

The Spring Boot service accepts XML trade submissions, validates and matches trades, calculates netting and generates local settlement instructions. Work moves through ingestion, matching and netting. Settlement instruction generation runs inside netting; the separate settlement queue is normally idle. Dead-letter messages remain available for investigation.

The demo profile configures 24 worker slots and a 16-connection application database pool. Queue polling uses a 250 ms interval. Operation IDs, message IDs and durable processing attempts support correlation across retries and restarts. HTTP 202 means admission, not completed processing.

The continuous feeder submits one request per second per running approach. Its mix includes matched pairs, unmatched trades, slow processing, retry recovery, exhausted retries, business rejection and malformed input. It pauses at configured backlog, retained-admission and free-disk limits. It does not automatically retry an uncertain POST.

This is a PostgreSQL-backed mock queue. The journal implementation has not been validated against a real MQ product. An actual broker deployment needs a supported activity-record adapter and explicit message/correlation mappings.

## Approach A: OpenTelemetry, metrics and durable history

### Implementation

- The OpenTelemetry Java agent supplies HTTP and JDBC instrumentation. Application spans describe components and queue processing; context travels with queued work.
- Micrometer provides runtime, worker, connection-pool, queue, latency and outcome metrics. The Collector exports traces to Tempo and logs to Loki. Prometheus stores metrics.
- Durable queue and attempt tables record outcomes, retry reasons, workers and operation relationships. Grafana queries restricted SQL support views for current state and persisted history.
- Three dashboards cover service overview, queue/stage diagnostics and operation investigation. Investigation selects the originating trace, renders its waterfall, and correlates logs by trace and operation IDs.
- Trace-to-logs links retain the selected trace and a padded span time range. The local Grafana Viewer configuration permits Explore access while dashboard write permissions remain absent. This setting is deprecated upstream; a production deployment needs authenticated support users and an appropriate Explore permission for its Grafana edition.
- Application launchers copy JARs to stable runtime paths. Building another approach no longer replaces a JAR used by a running JVM.

Main files: `mocknet/src/main/java/com/cit/mocknet/observability/`, `QueueMessageTracing.java`, `ComponentTracingAspect.java`, `observability/compose.yaml`, `observability/postgres/support-views.sql`, and `script/build-mocknet-dashboards.py`.

### Findings

A provides the most complete investigation path: affected operation, queue disposition, attempt and failure reason, component timing, JDBC call and related logs. Durable history remains useful when a trace is delayed or has expired.

Testing exposed two important operational issues. The running-JAR replacement problem was fixed by isolating runtime artifacts. As retained data grew, two overview queries reached their five-second timeout. Indexes on operation IDs and matched-trade legs, plus an indexed related-operation lookup, removed the timeout in subsequent checks.

A browser test reproduced a related-logs link redirecting to Home because the Viewer session lacked Explore access. The configuration fix was verified through the actual span link: four records showed two ingestion retries, successful ingestion and matching for the selected incident.

A remains broader than necessary at some helper-method boundaries. Production tuning should prefer meaningful request, queue, component and dependency boundaries, with an explicit sampling policy and retained failure evidence. A method return or a successful local queue disposition does not establish external settlement.

## Approach B: strictly JVM runtime metrics

### Implementation

- A separate Java-agent configuration disables tracing, logging and general application instrumentation. Only runtime telemetry is enabled.
- Its own Collector accepts the runtime metrics and exports them to a separate Prometheus instance. Trace and log receiver routes are absent.
- Three dashboards show JVM overview, runtime diagnostics and resource investigation. They include CPU, heap, allocation, GC, threads, loaded classes, buffers and file descriptors.
- Business IDs, queue metrics, HTTP metrics, database metrics, spans and trace-derived metrics are outside B's exported signal set.

Main files: `script/mocknet-approach-b.sh`, `observability/approach-b/`, and `script/build-mocknet-approach-b.py`.

### Findings

A recorded exercise admitted 373 requests, including concurrent load, a paused consumer, rejected trades and exhausted retries. All admissions returned HTTP 202 and an all-zero trace ID.

During the consumer pause, 27 messages accumulated while sampled JVM CPU was about 0.32%. After injected business failures, dead letters increased while CPU was about 0.48%. Database connection pressure made the database-backed status endpoint fail even though runtime metrics remained available.

Those queue counts came from the experiment's separate ground-truth recorder; B did not display them. The exercise demonstrates that low CPU and a reachable metrics pipeline do not establish business health. B is suitable as a runtime diagnostic layer, not a complete L3 service dashboard.

B is stopped at the user's request. Its database volumes, dashboard definitions and recorded findings are retained. Its earlier query and metric-boundary checks are historical validation, not a claim that B is currently running.

## Approach C: component logs and committed MQ journals

### Implementation

- The application runs without a Java agent and with application tracing disabled. A logging aspect emits structured component start, end and failure records.
- Records carry call/parent IDs, operation/message IDs, stage, worker, timestamps, monotonic duration and exception classes. Empty queue polls are not logged; failed claims identify their queue without inventing an operation association.
- Transaction-local database triggers journal allowed queue and attempt metadata. Payloads, exception messages and OpenTelemetry trace/span fields are excluded from this journal.
- A checkpointed Python collector pools component logs and committed journals into the separate `telemetry_c` reporting database. Grafana's read-only role cannot connect to the application database.
- Journal records are committed to reporting before source acknowledgement. Unique event IDs make replay idempotent. Collection does not rely on a sequence high-water mark, so lower sequence numbers committed late are still collected.
- Log checkpoints and inserts commit together. Partial lines wait for completion; rotated files retain identity. Malformed records are quarantined by position and hash. Retention removes older detailed evidence while preserving latest reconstructed queue state.
- `c_waterfall(operation_id)` converts reporting records into the native Grafana trace-frame format at query time. No Tempo instance or extra application instrumentation supplies C's waterfall.

Main files: `ComponentJournalAspect.java`, `observability/approach-c/journal.sql`, `reporting.sql`, `waterfall.sql`, `logback.xml`, `script/mocknet-c-reporter.py`, and `script/build-mocknet-approach-c.py`.

### Investigation experience

C's waterfall automatically fits the selected operation, independent of the dashboard time window. It shows nested and collapsible component calls, MQ attempts, retry delay, ready wait and processing intervals. Expanded rows expose original source IDs, worker, outcome, failure reason and evidence provenance.

Recorded parent-call IDs determine component nesting. Top-level calls join an attempt only when message ID, worker and time interval identify exactly one candidate. This inferred association is labeled. Missing parents, missing starts, open/missing ends and clock inconsistencies remain explicit.

The renderer calls these display rows spans and displays deterministic reconstruction IDs. These are not emitted OpenTelemetry IDs. Stage/evidence groups supply its service colors; the displayed service count is not a count of deployed services. Critical-path calculations describe only the reconstructed tree.

The retry example rendered 28 rows and two approximately 500 ms backoff intervals. Seven recorded scenarios, including a roughly 20-second queue stall, were checked. Waterfall queries took approximately 0.2–0.3 seconds in that local snapshot. Those timings are not a production benchmark.

### Findings

The main C incident exercise admitted 128 deliberate requests alongside its background stream. It covered stalled ingestion, concurrent load, rejection, exhausted retries, database pressure and collector outage/recovery.

A 12-second collector outage left newly processed operations absent from reporting until collection resumed. Replay, late commits, rollback, partial lines, duplicate lines, rotation, missing ends and quarantine behavior were exercised. A separate pressure check recorded failed claims in all four worker stages with transaction/connection exception classes.

C can reconstruct useful transaction and queue history. It still cannot supply unlogged SQL calls, JVM resource behavior or unobserved external dependencies. It reuses operation IDs and durable attempt/context structures developed for A, so it is not an independent zero-change instrumentation baseline.

The asynchronous logger can block application threads when full. Process termination, disk failure or rotation before collection can lose evidence. Gap counters cannot detect every missing tail or entirely lost file. The collector, retention, reconstruction rules, schema evolution and broker adapters require ongoing ownership.

## Measured application cost

The controlled local comparison used the same application artifact and ran in order A, B, C, C, B, A. Each run used a fresh database, Java 17, 128 MiB initial/512 MiB maximum heap, 24 workers, 16 database connections, 80 warmup requests and 400 measured requests at concurrency 16. Every run finished with all 480 trades NETTED.

Medians of two runs per approach:

| Approach | HTTP admission p95 | Submit and drain | JVM CPU time | Peak sampled JVM RSS |
| --- | ---: | ---: | ---: | ---: |
| A | 22.00 ms | 3.68 s | 10.49 s | 524.84 MiB |
| B | 24.78 ms | 3.85 s | 10.07 s | 616.30 MiB |
| C | 31.60 ms | 3.66 s | 9.08 s | 402.65 MiB |

C wrote approximately 22.3 KiB of component logs and 9.1 KiB of source journal allocation per trade, before the reporting copy. A separate live C reporter observation measured about 26 MiB RSS and 0.13 CPU seconds over 20 seconds.

These measurements exclude a controlled comparison of total collector/database/Grafana cost. C's collector did not consume the isolated benchmark databases. Other live feeds ran on the shared host, contention retries varied, and there were only two samples per approach. The later waterfall projection was not part of the original benchmark. C's smaller sampled JVM footprint does not establish the lowest total system cost.

## Laptop resource findings

A separate 22.66-second observation measured Docker's VM at 124% CPU, where 100% represents one logical core. A's PostgreSQL container used about 38–40% CPU in snapshots. Container CPU is included in VM CPU and must not be added to it. All demo containers, including B at that time, reported about 2.4 GiB memory; native application JVMs added roughly 0.7 GiB RSS.

The 24 GiB host had approximately 9.22 GiB physically occupied by memory compression and about 8.2 GiB of swap in use. The sample recorded 11.4 MiB of swap-ins and no swap-outs. No thermal warning was reported; no direct temperature measurement was taken. An unrelated process also consumed nearly one core and was removed at the user's request. These observations do not attribute all laptop heat to observability.

B was subsequently stopped. A and C continue at one admission per second. Lower idle polling, less frequent dashboard refresh and bounded retained data are practical tuning candidates, but a before/after controlled measurement is still needed to quantify their effect.

## Validation

Historical implementation validation passed 31 Java tests and 87 dashboard queries across A, B and C. After the native C waterfall change, all 20 C queries and 14 reconstruction contract checks passed. The contract test uses rolled-back reporting fixtures and verifies the actual Grafana database role.

Pre-push validation on September 15, 2026:

| Check | Result |
| --- | --- |
| Java, `mvn -q -f mocknet/pom.xml test` | 31 passed, zero failures/errors |
| Trace viewer, `bun test test/tool/traceview.test.ts` | 4 passed, zero failures |
| Live A dashboard queries | 39 passed |
| Live C dashboard queries | 20 passed, including native waterfall frame validation |
| C reconstruction contract | 14 checks passed; fixtures rolled back |
| Full CLI suite, `bun test` | 459 passed, 1 skipped, 101 failed |
| TypeScript, `bun run typecheck` | 44 compiler errors |

The full CLI suite and TypeScript checks were also run against unchanged base commit `e96ac84` in an isolated checkout using the same installed dependencies. It produced the same 459/1/101 test counts, the same failing test names, and the same 44 compiler diagnostics after normalizing line numbers. There were no additional failures in those comparisons. The repository-wide checks remain failing; these changes do not claim to repair the existing CLI configuration/skill-discovery failures or TypeScript errors.

Whitespace and syntax checks passed. The staged text scan found no remaining references to the removed organization name or its telemetry namespace. Local databases, Python cache files and generated secrets are excluded from this commit.

## Run and inspect

```bash
# Start A and initialize local generated secrets.
bash script/mocknet-grafana.sh start
bash script/mocknet-grafana.sh stream-start

# Start C with a separate backend, reporting store and Grafana.
bash script/mocknet-approach-c.sh start
bash script/mocknet-approach-c.sh stream-start

# Validate running A/C dashboards and C reconstruction.
python3 script/check-mocknet-dashboards.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-approach-c.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-waterfall.py
```

| Approach | Grafana | Application | State at report preparation |
| --- | --- | --- | --- |
| A | [localhost:3300](http://localhost:3300/d/mocknet-traces?from=now-15m&to=now) | localhost:18081 | Running with feed |
| B | Dashboards on A's Grafana | localhost:18091 | Stopped |
| C | [localhost:3302](http://localhost:3302/d/mocknet-c-overview?from=now-15m&to=now) | localhost:18101 | Running with feed |

Use `stream-stop` to stop only a feed and `stop` to stop its stack. Stop commands preserve database volumes. Local generated secrets and raw runtime evidence are excluded from Git under `.bootstrap/`; report summaries and reproducible scripts are included. Historical Grafana links depend on retained data and eventually expire.

The current source uses the neutral telemetry key `component.stage` and pipeline terminology consistently across instrumentation, trace viewers, examples and tests. Already-running binaries and previously collected records keep their original metadata until restart or retention expiry. This commit changes the current repository tree, not prior Git history.

## Production work remaining

- Authenticate support users, restrict datasource access and use managed credentials, TLS and an audited deployment process.
- Validate a real broker adapter, message correlation across services, clock behavior, duplicate delivery and abandoned claims.
- Define business SLOs, alert ownership and runbooks. Page on stalled work, persistent retries, exhausted attempts and loss of evidence, with thresholds based on the service's actual workload.
- Establish sampling, sensitive-field allowlists, retention, backup and restore requirements. Do not use high-cardinality operation IDs as metric labels.
- Load-test retained data volumes, query plans, collector outages, process kills and disk-full behavior. Add collector redundancy or partitioned projections where required.
- Measure end-to-end cost and diagnostic completeness with comparable signal coverage. B's lower diagnostic coverage makes a raw overhead comparison insufficient.

## Design references

- [OpenTelemetry Java instrumentation controls](https://opentelemetry.io/docs/zero-code/java/agent/disable/)
- [OpenTelemetry log data model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)
- [Grafana trace frame contract](https://github.com/grafana/grafana/blob/v12.4.1/packages/grafana-data/src/types/trace.ts)
- [Grafana log-label guidance](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/)
- [IBM MQ application activity trace](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=network-application-activity-trace)
- [Logback asynchronous logging](https://logback.qos.ch/manual/appenders-async-sift.html)

Further details: [approach comparison](observability/APPROACH-COMPARISON.md), [A implementation](observability/approach-a/README.md), [B validation](observability/approach-b/VALIDATION.md), and [C implementation](observability/approach-c/README.md).
