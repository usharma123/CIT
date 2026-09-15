# Comparison of Approaches A, B and C

## Recommendation

**Use A for L3 incident diagnosis. B requires the fewest application changes. Choose C when an agent is prohibited or a legacy estate already has sufficiently detailed, correlated logs and MQ activity records.**

C works, but it is a custom tracing/reporting system implemented through logs and journals. It needs an event contract, correlation, a collector, replay semantics, retention, query tuning and operational ownership. Fewer agents or containers do not automatically mean lower total cost.

For a production service, my preferred design is standard OpenTelemetry runtime/dependency instrumentation plus selective business/queue telemetry, with committed MQ journals as independent evidence. That uses A's diagnostic approach while avoiding unnecessary per-helper instrumentation. This recommendation is an engineering judgment based on the capabilities and local tests below, not a production performance ranking.

## Diagnostic coverage

| L3 question | A: spans, metrics, logs and durable history | B: JVM runtime metrics only | C: pooled component logs and MQ journals |
| --- | --- | --- | --- |
| Which trade or operation is affected? | Business/operation lookup and related operations | Unavailable | Explicit operation/business IDs in logs and journals |
| Where is work waiting? | Queue inventory, ready age, retries and attempts | Unavailable | Reconstructed committed state and queue wait intervals |
| Which component failed or ran slowly? | Spans, correlated logs, component and SQL timing | Unavailable | Logged component timing, exception/cause classes, journaled reasons |
| Did the queue transition commit? | Durable queue and attempt history | Unavailable | Transactional journal, independent of method-return logs |
| Which SQL call was slow? | JDBC span detail | Unavailable | Unavailable; only enclosing component time |
| Is GC, heap or CPU involved? | Runtime metrics | Strongest available evidence in B | Unavailable in C's configured signal set |
| How are retries or dead letters explained? | Committed attempts plus trace/log context | Unavailable | Committed attempt journal plus component evidence |
| Is a full distributed critical path available? | Broadest coverage in this demo, with explicit uninstrumented boundaries | Unavailable | Only logged/associated calls and queue records; gaps remain gaps |
| Does queue completion prove external settlement? | No, the external system is not observed | No | No; UI says queue work finished |

## What is lighter touch?

| Meaning of light touch | Assessment |
| --- | --- |
| Fewest application changes | **B.** Runtime-only agent configuration and disabling custom tracing. No business correlation contract is needed. |
| Application changes in this repository | **C is concentrated but not zero-change.** One component-logging aspect, logging configuration and journal triggers reuse operation IDs and the processing context built for A. A legacy app without those IDs needs additional work. |
| Least custom monitoring software to maintain | **B first, A next.** C adds our own collector, schema, pairing rules, recovery and retention logic. A uses standard trace/log/metric backends alongside its business views. |
| Lowest JVM memory in this local sample | **C.** See the bounded comparison below. This excludes reporting infrastructure. |
| Lowest CPU or latency | **No defensible general winner from this sample.** Small runs, retry races, JVM warmup and shared-host activity affect the results. |
| Best incident diagnosis per engineering effort | **A for this service.** C is credible where agent constraints justify maintaining a reconstruction pipeline. B alone misses business and queue incidents. |

## Controlled local application-cost test

`script/benchmark-mocknet-approaches.py` ran the same application artifact six times in order A, B, C, C, B, A. Every run used a fresh PostgreSQL database on the same server, Java 17, 128 MiB initial/512 MiB maximum heap, 24 consumers, 16 database connections, 80 warmup requests and 400 measured requests at concurrency 16. Every run ended with all 480 trades NETTED. Retry counts varied with contention and are retained in the raw results.

The table shows medians of two runs per approach. HTTP timing is admission latency, not completed-trade latency. Drain time ends when observed queue work is complete. JVM CPU seconds cover the measured workload; RSS is sampled resident memory, not heap usage.

| Approach | HTTP admission p95 ms | Submit and drain seconds | JVM CPU seconds | Peak sampled JVM RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| A | 22.00 | 3.68 | 10.49 | 524.84 |
| B | 24.78 | 3.85 | 10.07 | 616.30 |
| C | 31.60 | 3.66 | 9.08 | 402.65 |

C wrote about **22.3 KiB of component logs and 9.1 KiB of source journal storage per admitted trade** in these runs, including warmup. The journal measurement includes relation/index allocation. The pooled reporting copy adds further storage. This is a material cost even when application code changes are concentrated in one aspect.

**Scope:** this measures application-side costs. A/B exporters ran; C wrote component files and transactional journals, but the C collector did not consume the separate benchmark databases. Collector/backend CPU, network/export volume, durability latency and total storage were not controlled end to end. All three live demos and their feeds remained active on the same host. Two short runs per mode cannot establish production overhead percentages, leak behavior, capacity limits or statistical superiority.

Artifact SHA-256: `21b4cd0d71cf005ba000f88124bdb8487375ed84809646fca25c99b26930483b`. Raw final results: `.bootstrap/observability/approach-c/benchmark/results.json`. The earlier exploratory run remains in `results-initial.json`; it predates failure-only queue-claim logging. Live collector process/storage observations are saved separately in `reporter-cost.json`.

## Incident and recovery evidence

C's completed scenario run admitted **128 deliberate requests** alongside its one-request-per-second background stream. It tested paused ingestion, a 120-request concurrent burst, exhausted retries, rejection, database pressure and collection outage/recovery.

The paused probe waited approximately twenty seconds before ingestion. Grafana reconstructs its queue-wait interval independently of the much shorter component execution. [Open the stalled operation](http://localhost:3302/d/mocknet-c-investigation?var-search=5176aa8e-226b-4734-ad3e-d4c27232350a&var-operation=5176aa8e-226b-4734-ad3e-d4c27232350a&from=1789484847463&to=1789484868113).

The collector stopped for twelve seconds while the application kept processing. Its age became stale, and the new operation was absent from reporting until collection resumed. The collector then caught up. The tests also passed:

- journal replay without duplicated reporting records;
- lower journal sequence committed after a higher sequence;
- rollback of an actual queue mutation with no journal/report record surviving;
- partial line completion, duplicate log replay and file rotation;
- missing component end detected, then paired when its end arrived;
- malformed line quarantined, with its payload excluded;
- journal payload and OpenTelemetry trace/span fields absent.

A subsequent database-pressure check captured failed claims in INGESTION, MATCHING, NETTING and SETTLEMENT, with `CannotCreateTransactionException` and `SQLTransientConnectionException`. These failures had no claimed operation; C shows their queue/component rather than inventing an operation association.

One quarantined line was intentionally inserted by validation. Initial setup logs retain the file-stat compatibility error fixed before the scenario run. A browser check found Grafana's custom All value bypassed normal SQL quoting; the provisioned All value was corrected. The original state timeline was replaced with the native Grafana waterfall. The reporting projection preserves recorded call parents and labels inferred attempt relationships, retry delay, ready wait, and missing evidence. The twenty-second wait was visually verified.

Final checks passed all 31 Java tests and 87 dashboard queries across A, B and C. C's 20 panels query only the pooled reporting store. During a separate twenty-second live-feed observation, the C reporter used about 26 MiB RSS and 0.13 CPU seconds. This excludes PostgreSQL and Grafana work. At that point C had about 12.1 MiB of source journal storage, 74.2 MiB in its reporting database, and 25.0 MiB of component files. These are a growing demo's snapshots, not steady-state capacity estimates.

The growing live feed exposed two A overview queries hitting their five-second timeout. Indexes on trade operation IDs and both matched-trade legs, plus an indexed operation-ID array lookup, removed that timeout in the final check. The change is in `observability/postgres/support-views.sql`; the original A archive remains intact. Query timing evidence is in `.bootstrap/observability/approach-a/query-performance-after-indexes.json`. This is another reason to test both the instrumentation and the dashboard queries under retained data volume.

Evidence lives in `.bootstrap/observability/approach-c/incident-results.json`, `claim-failure-validation.json`, `dashboard-validation.json` and `reporter-cost.json`. Java tests cover nested log pairing, failure preservation, thread-local cleanup, omission of sensitive data, and failure-only claim logging in addition to existing business/concurrency tests.

## Sources behind the design

OpenTelemetry defines explicit log correlation fields, supporting the decision to carry identifiers rather than infer relationships from nearby timestamps. [OpenTelemetry logs data model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)

IBM's supported application activity trace can provide more detailed MQ activity than basic monitoring. This demo's PostgreSQL journal is an adapter example, not validation against IBM MQ. [IBM MQ application activity trace](https://www.ibm.com/docs/en/ibm-mq/10.0.x?topic=network-application-activity-trace)

Grafana advises against using operation/trace IDs as index labels. C keeps them in searchable reporting fields. [Grafana label guidance](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/)

Logback documents the blocking/dropping tradeoff of asynchronous logging. C disables discarding and accepts backpressure if its bounded queue fills. [Logback asynchronous appenders](https://logback.qos.ch/manual/appenders-async-sift.html)
