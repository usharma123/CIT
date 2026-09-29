# C exploration parity

Approach C now provides the Grafana exploration workflow available in A while the application remains agent-free. Individual SQL-call timing is excluded. Alarms and notifications are outside this work.

## Coverage

| Capability | C implementation | Meaning and limit |
| --- | --- | --- |
| Traces Drilldown, search, rate, errors and duration distributions | Dedicated Tempo with TraceQL local blocks | Reconstructed, settled-operation snapshots; not every admitted operation |
| Service structure and trace waterfall | Stable trace/span IDs and recorded parent IDs | Select **All spans** for internal structure. One deployed service, `mocknet`; stages are attributes |
| Current or incomplete operation waterfall | Existing reporting SQL projection | Remains current when export is delayed or a frozen trace has newer evidence |
| Logs Drilldown and exact trace/operation correlation | Structured application diagnostics and source evidence in dedicated Loki | Logs, call starts/ends and journals are observations, not extra transactions |
| Runtime and queue metrics, Metrics Drilldown | Agent-free Micrometer scraped by dedicated Prometheus | JVM, GC, CPU, HTTP admission, pools, queue inventory, attempts and retries |
| Business state, recovery and related legs | Committed trade/matched-leg journals and reporting views | One operation per admission; recovery is a subset; shared queue work is counted once |
| Individual JDBC calls | Excluded | Enclosing component and pool measurements remain available |

Open [C overview](http://localhost:3302/d/mocknet-c-overview), [runtime metrics](http://localhost:3302/d/mocknet-c-runtime), or [operation investigation](http://localhost:3302/d/mocknet-c-investigation). Dashboard navigation links open C's Traces, Logs and Metrics Drilldown with the correct datasources. All five dashboards contain 55 panels in total.

## Delivery and counting

`export.sql` adds delivery records to the reporting database. The existing evidence primary key deduplicates journal/file replay before export. Application log fields are allowlisted; payloads, SQL, credentials and exception messages are omitted. Operation, trace and event IDs stay in Loki structured metadata/JSON, outside indexed labels.

`bridge.py` runs in a separate process. It requires fresh collection, no pending journal entries, no unfinished queue work, no open component calls, and 15 seconds without new evidence before freezing a trace. It records the source version, payload and expected span IDs before sending. The application continues without waiting for Tempo or Loki. Waiting for a counterparty can still qualify as locally quiescent; later matching evidence is shown as a snapshot change.

Each operation has one export record. An accepted trace is not resent. A refused connection can retry because the request was not delivered. Interrupted requests, timeouts, partial acceptance and ambiguous server failures become `uncertain`; permanent request failures become `rejected`. Recovery checks whether Tempo contains the complete expected span set and can confirm acceptance without another POST. Ambiguous Loki delivery remains visible for investigation. This avoids blind replay and potential duplicate metrics; it does **not** guarantee exactly-once delivery across HTTP.

Later evidence advances the operation's source version and the UI says `new evidence after snapshot`. Use the live SQL waterfall and raw records for the current view. No second root is exported to patch the old trace. Pending, unresolved and changed snapshots appear on Sources and L3 diagnostics. Retention keeps undelivered log evidence and keeps delivery records while their evidence remains, so a restart cannot backfill them again. Keep the delivery ledger when restarting or migrating; rebuilding it from retained evidence could cause re-export.

Business totals come from committed reporting views. Tempo root count means exported operation snapshots, all-span count includes parent groups and child observations, and Loki count means records. Do not add these counts together or sum nested span durations. C's root duration covers the reconstructed operation envelope; A's HTTP root measures admission. Compare HTTP admission using the same Micrometer metric on both sides.

The log delivery queue stores event timestamps and indexes only pending records in timestamp order. Each cycle sends up to five batches of 1,000 records; selection does not scan delivered history, and acknowledgements use event primary keys. Catch-up stops on a failed delivery and preserves the existing uncertainty policy. Oldest-first export avoids advancing a Loki stream with current records ahead of its queued history. Loki can still reject records outside its out-of-order or age limits; a queued record is not proof of acceptance.

## Storage and delayed evidence

C has independent Tempo (3202, OTLP HTTP 24318), Loki (13102), Prometheus (19092), Grafana (3302) and reporting PostgreSQL (15452), all exposed on loopback. The launcher starts the exporter after backend readiness. Use `telemetry-start` / `telemetry-stop` to control it separately from collection.

Tempo's generator, trace WAL and generator trace WAL allow one hour of timestamp delay. Its default short allowance excluded earlier reconstructed records from metrics and bounded search even when trace-by-ID worked. Older exports made before this correction can retain those gaps; they are not blindly resent. The initial backfill selects the 15 minutes before export was enabled. Delays beyond one hour need an explicit recovery policy and validation; this configuration does not promise complete historical TraceQL metrics. The reporting history remains the source of retained business evidence.

Historical application-cost results predate this exporter, application log stream, Micrometer scrape and telemetry stack. They cannot establish the current C footprint or its total operating cost. The report revises remaining hosted-pilot work to include these components.

## Validation

```bash
.bootstrap/observability/approach-c/venv/bin/python -m pytest observability/approach-c/telemetry/tests observability/approach-c/backend/tests
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-waterfall.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-approach-c.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-parity.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-api.py
```

The live parity check needs recent settled feed traffic. It compares Tempo IDs with the durable ledger, checks one root and exactly the expected number of all spans in TraceQL, performs bounded trace search, checks unique correlated Loki records, and verifies runtime metrics. Rolled-back fixtures exercise interrupted delivery, late evidence and duplicate event replay without sending test data to the backends. Results are written under `.bootstrap/observability/approach-c/`.

Validation passed 31 Python tests, 16 waterfall fixtures, all 55 dashboard queries and the support API access/diagnostic checks. Java test reports contain 33 passing tests. Browser verification on September 15 showed populated Logs and Metrics Drilldown, a working trace-to-logs link returning 39 records, runtime charts and nested Service structure with ingestion, matching, retry delay, ready wait and component calls. A selected real trace contained 18 total spans, one root, and 39 unique correlated log/evidence records. After pausing the feed and allowing collection to catch up, source/reporting counts matched for 13,678 operations, all trade and queue states, 33,676 attempts, and 8,124 directed matched-leg links. Export health showed zero pending logs and zero unresolved deliveries; 374 operations were still awaiting settled evidence. The feed was restored afterward. These are bounded local checks, not a production losslessness or capacity guarantee.

Implementation references: [OTLP delivery semantics](https://opentelemetry.io/docs/specs/otlp/), [Loki push API](https://grafana.com/docs/loki/latest/reference/loki-http-api/), [Tempo datasource correlation](https://grafana.com/docs/grafana/latest/datasources/tempo/configure-tempo-data-source/), and [Tempo 2.9 configuration](https://github.com/grafana/tempo/blob/v2.9.0/docs/sources/tempo/configuration/_index.md).
