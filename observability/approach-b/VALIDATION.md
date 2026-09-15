# Approach B validation — 15 September 2026

## Reproduce and inspect

Run `python3 script/mocknet-approach-b-demo.py` with B and its background feed running. This is a local fault exercise, not a capacity or production-readiness certification.

The completed run admitted **373 requests** (360 concurrent-load requests in three batches, a paused-consumer probe, six retry-exhaustion cases and six rejected-currency cases). Every admission returned HTTP 202 and an all-zero trace ID. The independent background stream continued at one request per second.

[Open the recorded incident in Grafana](http://localhost:3300/d/mocknet-b-investigation?from=1789483208414&to=1789483353522)

Experiment interval: **14:40:38–14:42:03 UTC** (10:40:38–10:42:03 America/New_York). Retention is 72 hours, so this live historical link will eventually age out. Raw observations remain in `.bootstrap/observability/approach-b/incident-results.json`.

## Measured observations

CPU and heap below are snapshots at each checkpoint, not interval peaks. Queue and dead-letter counts come from the experiment's separate ground-truth recorder; B cannot display these counts. Shared host activity, concurrent background traffic and five-second metric export/scrape affect timing.

| Checkpoint | JVM CPU | Heap MiB | Ingestion ready (ground truth) | Dead letters (ground truth) |
| --- | ---: | ---: | ---: | ---: |
| baseline | 0.518% | 165.4 | 1 | 26 |
| consumer-stall | 0.323% | 202.1 | 27 | 26 |
| consumer-recovered | 1.242% | 148.8 | 0 | 29 |
| 360-concurrent-submissions | 3.055% | 195.1 | 0 | 30 |
| business-failures-and-rejections | 0.477% | 165.6 | 0 | 36 |
| database-connection-pressure | 0.415% | 186.6 | unavailable | unavailable |
| database-recovered | 0.070% | 208.6 | 0 | 36 |
| explicit-gc | 1.469% | 127.8 | 0 | 37 |

- Pausing ingestion accumulated 27 messages while sampled CPU stayed low. After resuming consumers, the ready count returned to zero. JVM metrics did not reveal the queue, age of waiting work or affected trades.
- The concurrent load produced a higher CPU sample. This establishes a visible runtime response; 360 admissions are not a measured maximum service capacity.
- After injected business failures, dead letters increased from 30 to 36 while CPU was 0.477%. Runtime telemetry contains no retry count, rejection cause or business identifier.
- Database pressure made the database-backed status endpoint return HTTP 500 while JVM metrics remained available. Thread counts alone did not identify the exhausted connection pool.
- Explicit GC succeeded through `jcmd`. The next heap sample was 127.8 MiB, versus 208.6 MiB at the preceding checkpoint; cumulative GC duration increased by approximately 63 ms. Heap samples include intervening allocations and cannot isolate all effects of GC.

**Conclusion:** B is useful for examining JVM resource behavior during a reported incident. It cannot provide the queue and transaction diagnosis in A. Neither low CPU nor a responsive metrics pipeline establishes business service health.

## Verification

- 29 Java tests passed, including concurrency, end-to-end, tracing and durable attempt history tests.
- All 39 tested Approach A dashboard queries returned frames without query errors.
- All 28 Approach B queries returned frames without query errors across three dashboards and 22 time-series panels.
- The final check verified 198 live JVM metric series as service `mocknet-b`, scope `io.opentelemetry.runtime-telemetry` (191 at the initial check); no unexpected application metric family appeared.
- B OTLP trace and log endpoints returned HTTP 404. A non-JVM metric probe was accepted at OTLP transport level and dropped by the collector; it was absent from Prometheus.
- The browser displayed populated JVM charts and the investigation navigation preserved the time range and resource selectors.
- Generated dashboard definitions and launch scripts are stored in the repository; A and B use separate runtime JARs, processes, databases, collectors and metric stores. They share Grafana and the physical host.

Evidence: `.bootstrap/observability/approach-b/dashboard-validation.json`, `incident-results.json`, `incident-run.log`, and `.bootstrap/observability/approach-a/current-dashboard-check.json`.

## Failures retained during setup

The initial experiment recorder stopped when database pressure caused `/api/status` to fail. Its results and log are retained as `incident-results-initial.json` and `incident-run-initial.log`. The recorder now records that unavailable ground truth and continues through recovery. The complete rerun above passed.

Building B replaced Maven's target JAR while the earlier A launcher was still running directly from it, causing A class-loading failures. A was restored from its checksum-verified original artifact. The launchers now copy build artifacts to separate runtime paths and detach JVMs from the launching terminal session. The original source/archive checksums remain unchanged; prior application logs and seeder failure counters are retained. These setup failures are not counted as successful incident scenarios.
