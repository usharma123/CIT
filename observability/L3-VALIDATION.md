# L3 validation — 2026-09-15

## Result

The backend and all five monitored telemetry targets were running after validation. The local stack uses PostgreSQL and 24 consumers in one application JVM.

- **482 submissions** in the incident suite `20260915T133628-ebb7`; all asserted admission, outcome, alert and recovery checks passed.
- **120 trades / 60 matched pairs** in `20260915T133858-dfd9` completed netting and instruction generation under concurrent submission.
- **15 additional trace scenarios** in `20260915T133859-6dff` passed business-state, root/consumer span and TraceQL search assertions.
- **29 Java tests passed**, zero errors/failures. New checks cover rollback-consistent history, retry-reason retention and obsolete-worker completion after claim recovery.
- **47 dashboard queries** executed through Grafana without errors. Prometheus configuration and all **8 alert rules** passed `promtool` validation.
- Read-only Grafana role verified: `default_transaction_read_only=on`, `statement_timeout=5s`, no raw queue-table SELECT or UPDATE privilege. Unauthenticated fault controls returned 403.

The 482-request suite finished in 127.8 seconds, including intentional waits and restarts. This is not a sustained throughput benchmark. The matched-pair and trace suites are separate runs, not extra samples of the same fault experiment.

## Incident evidence

| Scenario | Observed result |
| --- | --- |
| Concurrent admission | 240 requests accepted with 24 client threads; all reached AWAITING_COUNTERPARTY |
| Matched trade load | 60 distinct pairs / 120 operations reached COMPLETED |
| Paused ingestion | 80 ready messages with no consumer attempt yet; oldest ready age 32.70 s; backlog alert fired |
| Queue wait vs handler delay | Longest ready wait 1.147 s; longest handler 0.325 s |
| Retry recovery | 8 operations, 16 retried ingestion attempts, 8 successful final ingestion attempts; no terminal failures for these operations |
| Retry exhaustion | 4 HTTP 202 admissions subsequently became terminal failures after 3 attempts each |
| Duplicate submission | Original and duplicate have different operation IDs; duplicate failed and did not become a second trade |
| Actual Hikari exhaustion | 16 active connections, 25 waiting threads; database-pool alert fired |
| Stale telemetry during DB pressure | Queue snapshot age reached 18.01 s; old values were identifiable as stale; 24 admitted operations recovered |
| Tempo outage | 12 operations progressed while Tempo was unavailable; 4 exporter batches buffered; requested trace available after restart |
| JVM crash under load | All 80 admitted operations recovered; 4 abandoned claims retained; replacement attempts carry version l3-demo-v2 |
| Log correlation | Actual Loki entries matched the selected trace ID and contained operation/business/stage/outcome metadata |

## Open the evidence

- [Incident metrics and alert history](http://localhost:3300/d/mocknet-stages?from=1789479383937&to=1789479526778)
- [Recovered retry operation](http://localhost:3300/d/mocknet-operation?from=1789479383937&to=1789479526778&var-business_id=303e61e7-ebd9-441d-a264-16a291ad3363&var-trace_id=b0202540d45e251d6f32d2181c2c0212)
- [Terminal failure after HTTP 202](http://localhost:3300/d/mocknet-operation?from=1789479383937&to=1789479526778&var-business_id=15eee008-776c-45f6-af50-d4da72c1c510&var-trace_id=b8bec0ba9138dae8bc147eeeec189113)
- [Work delayed by paused consumers](http://localhost:3300/d/mocknet-operation?from=1789479383937&to=1789479526778&var-business_id=3849e1a4-719e-40e9-af74-adc9bfd69238&var-trace_id=770bc9227325a1947f8501ed59c6e156)

Browser verification confirmed the overview cards, historical queue charts and alert timeline, current operation state, both sides of a matched trade, and an embedded 108-span waterfall. Historical links retained the absolute interval. The browser also displayed the recovered operation's two concurrency-conflict retry logs followed by ingestion and matching completion logs.

## Evidence files

Under `.bootstrap/observability/` (ignored runtime artifacts):

- `l3-results.json`, `l3-scenarios.log`: each admission plus fault assertions.
- `l3-paired-results.json`, `l3-paired.log`: matched-pair load.
- `demo-results.json`, `l3-baseline-demo-final.log`: final trace suite.
- `l3-dashboard-check.json`, `l3-access-check.json`, `l3-final-tests.log`.
- `l3-results-initial.json`, `l3-scenarios-initial.log`: retained initial attempt. Its harness failed on a transient socket close immediately after restarting Tempo; the restart wait was fixed, and the complete suite above passed. No failed attempt was discarded.

No production capacity, HA, tenant isolation, SSO, paging delivery or real external settlement claim is made. See [deployment requirements](README.md#before-a-real-production-rollout).

## Overview revision

The overview now has seven investigation panels and zero stat cards. It starts with affected operations and direct investigation links, followed by failure reasons, waiting/processing trends and a compact outcome table. Reachability and freshness remain in stage diagnostics. Updated panel-query validation is saved in `l3-overview-check.json`; prior workload results above are unchanged.

## Trace visibility repair

The investigation landing table previously omitted trace IDs, later tables placed them after many columns, and Investigate links did not select a trace. The repair puts full clickable IDs first, populates a Trace ID selector from the selected operation and related work, and carries the originating trace through overview and queue investigation links. The waterfall now sits directly below the operation table.

Live browser verification followed an overview Investigate link for operation `6d07531c-1d2b-4337-9baa-7fff8a31db00`; trace `ed680bae14bb0595eba8ffc6bcb17ad8` was selected automatically and its 112-span waterfall rendered. At the initial check, all 2,120 operation records and 4,858 attempt records had trace IDs; the reported issue was presentation/selection, not missing persisted correlation. Query validation is in `trace-visibility-check.json`.
