# A and C: trace detail and data correctness

Verified locally on 2026-09-15. Grafana remains the UI. No alarm or notification changes.

## Why A looked busier

A combined Java-agent HTTP/JDBC spans with an aspect around virtually every Spring controller, service, helper and repository method. C reconstructs component calls and queue intervals from logs and committed journals, without the Java agent's JDBC coverage.

The extra repository wrappers were separate nested observations of the same work. They did not demonstrate duplicate SQL execution. Repeated SQL span names can also be separate real queries. Span ID, attempt ID and operation ID are different identities.

A now records explicit business boundaries by default: admission, queue publication, ingestion, matching, netting, two-phase coordination and the settlement handler when that path actually runs. Queue consumer and Java-agent JDBC/HTTP spans remain. Temporary detailed component tracing is available with `mocknet.tracing.verbose-components=true`.

| Comparable local scenario | A before | A after | JDBC spans before / after |
|---|---:|---:|---:|
| Matched second leg, including netting | 112 | 57 | 45 / 45 |
| Normal unmatched operation | 36 | 20 | 12 / 12 |
| Two ingestion retries, then recovery and matching | 50 | 30 | 18 / 18 |

These are span counts for comparable paths, not a latency benchmark. Old stored traces keep their original detail. C's fewer spans indicate narrower evidence coverage, not automatically better performance.

## Counting rules and evidence

- **Operations:** count admitted operation IDs. A matched pair has two submitted operations. Recovery is a subset of outcomes, not an additional operation.
- **Attempts:** count committed attempt IDs. Two retries followed by success are three attempts for that queue message. Attempt numbering restarts for the next message.
- **Related work:** operation detail deliberately includes both trade legs. The SQL uses a distinct union of operation IDs. Shared netting appears once in the combined table. Selected/Related labels and the full attempt, message and operation IDs make this scope visible.
- **Metrics:** counters are published after commit. Tests confirm rollback adds zero successes, repeated completion adds only one, and two retries followed by completion add exactly three outcomes.
- **Queue depth:** the gauges describe one shared database. Take the maximum across eligible exporters, not their sum. The dashboards now exclude failed scrapes and database snapshots at least 15 seconds old. A missing value remains a gap. This aggregation is not appropriate for independent databases without an additional database dimension.
- **C projections:** raw evidence is unique by event ID; queue and attempt projections choose the latest journal version by entity ID. Component starts/ends represent one call. A replay of the same event ID does not add another row.

The final isolated A audit submitted six operations and recorded 13 attempts. Metric increments exactly matched committed outcomes: ingestion 4 completed, 2 retried, 1 rejected, 1 failed; matching 4 completed; netting 1 completed. Each stored attempt span ID matched one exported consumer span. Span IDs were unique within each trace, and all exported parent references resolved.

The six baseline traces also had no duplicate span IDs. Whole-view A identity checks found no duplicate operation IDs, attempt IDs or message/claim-time pairs. C's live audit found no duplicate IDs across 389,224 evidence records, 21,118 messages, 22,964 attempts, 127,342 calls and 9,335 operations. These are snapshots of growing local stores.

## Visual corrections

1. **Inclusive durations:** a parent span already includes its child intervals. A 100 ms handler with 80 ms of SQL is not 180 ms of elapsed work. Waterfalls retain nesting; operation counts and outcomes come from committed records, not span counts or counts of error-marked spans.
2. **Timing boundaries:** the recorded attempt duration runs from claim to the disposition timestamp. It includes bookkeeping before that timestamp and excludes final commit/export. The consumer span starts later. Both A and C now label the recorded quantity **Attempt elapsed**. Ready wait and scheduled retry delay remain separate.
3. **Unknown completion:** processing and abandoned attempts have no observed execution completion duration. Their table duration is now null, rather than zero. C's abandoned waterfall bar represents claim-to-reclaim time and explicitly warns that actual completion/execution duration is unknown. An unfinished bar extends to collector observation without claiming the worker is still executing.
4. **Log selection:** exact IDs replace prefix matching. A selected trace takes precedence over the operation filter, preserving logs when inspecting the related trade leg. Truncated IDs were tested to return no matches. Grafana's scalar `doublequote` interpolation was verified in the browser.
5. **Labels and scope:** two-phase coordination is tagged NETTING in the current path. It does not prove external settlement. The operation summary says **Elapsed to outcome**. Attempt tables show selected/related leg, stage, result and durations before secondary identifiers. Instance labels distinguish exporter/pool series. Current-state and time-window scope remain explicit.
6. **Percentiles:** A combines histogram bucket rates before estimating p95. It does not average instance p95 values. C computes sample percentiles in 30-second buckets. Their estimates/windows differ, so the two charts are not a matched performance comparison. See the [Prometheus histogram guidance](https://prometheus.io/docs/practices/histograms/).
7. **Waterfall validation:** the A query checker now includes the native trace panel and verifies nonempty data. It mirrors the installed Grafana Tempo frontend's conversion from a hexadecimal `traceql` input to the backend `traceId` query type. The dashboard retains the frontend-supported type.

## Validation and limits

- 15 relevant Java tests passed across tracing, queue identity, committed metrics, end-to-end processing and C component journaling. The test databases were isolated in-memory H2 databases.
- All 40 A datasource queries passed, including the native waterfall. The selected matched trace's Grafana frame contained 57 unique spans with valid millisecond timestamps/durations.
- All 24 C panel queries passed through Grafana's reporting datasource. The C read-only role still cannot connect to the application database.
- C waterfall fixtures passed 16 checks, including unique IDs, parent references, retries, missing start/end, clock inconsistency and abandoned-duration semantics. Fixtures were rolled back.
- Browser inspection confirmed A's 57-span native waterfall, selected/related attempt rows, units and three exactly correlated stage-completion logs.
- The C snapshot had zero pending journal records, incomplete calls and internal sequence gaps, plus one quarantined line. That quarantine remains visible; this review does not claim every historical source record is complete.
- A and C continuous local feeds were restored at one request per second. Only A's executable was redeployed, as `l3-concise-v3`; C's reporting views and dashboard labels were updated.

Evidence files are under `.bootstrap/observability/approach-a/noise-audit/`: `before-summary.json`, `after-summary.json`, saved Tempo responses, `dashboard-queries.json`, `c-identity-audit.json`, `c-waterfall.json`, and test logs. The previous A executable is retained as `app-before.jar`.

To repeat the bounded A audit, stop its continuous feed, allow active work to finish, run `python3 script/check-mocknet-a-evidence.py`, then restart the feed. It submits six local demo operations. C checks use `.bootstrap/observability/approach-c/venv/bin/python`.

## Follow-up: built-in Traces Drilldown

The earlier validation covered custom dashboards and waterfalls, not Grafana's separate Traces Drilldown plugin. On 2026-09-15 its rate query returned HTTP 500 with `empty ring` because the Tempo metrics generator was not configured. Enabled the Tempo 2.9 `local-blocks` processor with persistent generator WAL/trace storage, all span kinds and historical flushing, then restarted only Tempo. The collector's persistent export queue remained enabled.

Five TraceQL metric queries and the Grafana datasource API passed. The affected Chrome tab now displays span-rate, duration histogram and service breakdown charts without the query error. Evidence is in `.bootstrap/observability/approach-a/drilldown-audit/`. History starts when the generator was enabled at about 17:57 UTC; previous trace data was retained. The plugin's root-span error rate measures admission-span errors, not asynchronous business failures.
