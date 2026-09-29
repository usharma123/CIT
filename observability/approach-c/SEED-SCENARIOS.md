# Fixed-time SLA scenario data

The mock business dataset is stored in schema `mocknet_c_demo` in the existing `telemetry_c` database. It has 24 scenarios, 72 SLA rows and 96 stage rows. It is synthetic demo data, separate from the 91 HTTP-seeded trades in `public`. It does not emit additional application traffic or enqueue telemetry exports.

The existing timeline renderer and dashboard files were not changed to connect this dataset. Claude's dashboard work should read the schema-qualified tables and functions below. **Use the stored SLA results and observation time. Do not recalculate open durations against `now()`.**

```sql
SELECT dataset_id, as_of, provenance FROM mocknet_c_demo.dataset;
SELECT scenario_id, label, operation_id, expected_sla, observed_sla
FROM mocknet_c_demo.scenarios ORDER BY ordinal;
```

The manifest is `.bootstrap/observability/approach-c/seeds/sla-scenarios-v1.json`. Its `timeRange` supplies the fixed overview window, and `asOf` is the observation clock. Scenario operation IDs are deterministic. Re-running the fixture seeder reuses the same dataset and timestamp.

## Data contract for the dashboard

These objects preserve the column names and types of the existing reporting queries. Only the schema qualifier changes; the timeline drawing logic can stay as it is.

| Existing object | Fixed demo equivalent | Contents |
| --- | --- | --- |
| `c_process_summary` | `mocknet_c_demo.c_process_summary` | Trade state, milestone timestamps, retry and output counts |
| `c_process_sla` | `mocknet_c_demo.c_process_sla` | All policy results, targets, elapsed time and per-scenario observation freshness |
| `c_process_stages(operation)` | `mocknet_c_demo.c_process_stages(operation)` | Four stage intervals, statuses, durations, retries and readiness |
| `c_process_timeline(operation)` | `mocknet_c_demo.c_process_timeline(operation)` | Recorded state transitions |
| `c_process_events` | `mocknet_c_demo.c_process_events` | Full event fields and JSON records |
| `c_operations` | `mocknet_c_demo.c_operations` | Operation selector and admission cohort |
| `c_attempt` | `mocknet_c_demo.c_attempt` | Queue waits, retries and attempt outcomes |
| `c_queue` | `mocknet_c_demo.c_queue` | Latest queue state at the observation time |
| `c_operation_links` | `mocknet_c_demo.c_operation_links` | Shared matched-leg relationships |
| `c_sla_policy` | `mocknet_c_demo.c_sla_policy` | Validation 5s, matching 30s, readiness 60s |
| `c_evidence_health` | `mocknet_c_demo.c_evidence_health` | Dataset observation metadata, not live infrastructure health |
| `evidence` | `mocknet_c_demo.evidence` | All underlying synthetic journal fields |

For example, the existing timeline segmentation query can call `mocknet_c_demo.c_process_stages(${operation:sqlstring})`. Use `mocknet_c_demo.c_process_sla` for the overview, SLA table and charts so all views agree. Keep actual runtime/Tempo/Loki diagnostics on their real datasources; these fixtures do not claim to be measured application traces or logs.

## Scenario coverage

| Scenario | Validation | Matching | Readiness |
| --- | --- | --- | --- |
| Healthy with overlapping stages | Met | Met | Met |
| Slow validation | Breached | Met | Met |
| Late counterparty | Met | Breached | Met |
| Slow instruction generation after timely matching | Met | Met | Breached |
| All deadlines missed | Breached | Breached | Breached |
| Exactly 5s / 30s / 60s | Met | Met | Met |
| Two retries, then recovered | Met | Met | Met |
| Ingestion retries exhausted | Failed | Failed | Failed |
| Rejected promptly | Met | Excluded | Excluded |
| Rejected late | Breached | Excluded | Excluded |
| Validation in progress | In progress | In progress | In progress |
| Validation at risk | At risk | In progress | In progress |
| Counterparty wait in progress | Met | In progress | In progress |
| Matching at risk | Met | At risk | In progress |
| Generation at risk | Met | Met | At risk |
| Counterparty wait overdue | Met | Breached | Breached |
| Netting failure | Met | Met | Failed |
| Shared netting failure, owning leg | Met | Met | Failed |
| Shared netting failure, matched leg | Met | Met | Failed |
| Zero net, no instruction required | Met | Met | Met |
| Partial instruction evidence | Met | Met | Breached |
| Sent without recorded generation | Met | Met | Unknown |
| Imported baseline | Unknown | Unknown | Unknown |
| Stale observation | Stale | Stale | Stale |

Missing or inapplicable timestamps remain null in the data. For example, an unfinished process has no completion time and a zero-net process has no instruction-generation interval. These are intentional scenario facts, not fields to fill with fabricated values. The status fields explain them.

## Create and verify

```sh
.bootstrap/observability/approach-c/venv/bin/python script/seed-mocknet-c-sla-fixtures.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-sla-fixtures.py
```

The seeder evaluates the real reporting SQL against an isolated temporary schema, checks every expected SLA result, then stores the resulting rows. It drops the temporary schema before committing. It never updates the live collector clock or live evidence. The checker verifies the read-only Grafana role, all expected statuses, stage chronology and duration, exact deadlines, overlapping stages, shared failures, partial outputs, and fixed-table fingerprints. Its receipt is `.bootstrap/observability/approach-c/fixed-sla-validation.json`.
