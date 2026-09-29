# Business process timeline and SLA

Open [Trade operations](http://localhost:3302/d/mocknet-c-business). Start it with `bash script/mocknet-approach-c.sh start`, then run one bounded local HTTP workload with `bash script/mocknet-approach-c.sh seed`. Each seed writes an observed-outcome manifest under `.bootstrap/observability/approach-c/seeds/`.

The home view summarizes trade admissions in the selected time window. Select an operation to open its retained history in the process detail dashboard. Dates use the dashboard timezone; calculations use elapsed UTC seconds.

## Working SLA defaults

These defaults are chosen for the local trade workflow and are labelled demo policies in Grafana. Edit `c_sla_policy` in the reporting database to change them. Provisioning preserves existing policy values.

| Policy | Start | Finish | Warning | Limit |
| --- | --- | --- | --- | --- |
| Validation | Receipt | Committed validation or rejection | 4 seconds | 5 seconds |
| Matching | Receipt | Committed match | 24 seconds | 30 seconds |
| Instruction readiness | Receipt | Netting complete and every required instruction generated | 48 seconds | 60 seconds |

Waiting and retry time count. Exactly meeting the limit passes. A late completion remains breached. Rejected trades are excluded from matching and instruction readiness. A processing failure remains failed. Successful retry recovery does not itself breach a policy. The compliance percentage counts only completed, measurable outcomes; unknowns, failures and unfinished work are visible in the process table rather than counted as successes.

The policies use wall-clock time, without business-hour calendars. They measure instruction readiness, not delivery, acknowledgement or external settlement. Current policy values apply to all retained records; historical policy versioning is not implemented.

## Evidence

`business-journal.sql` captures netting output IDs, whether an instruction is required, instruction state, and the two related operation IDs. These records commit or roll back with the application transaction. They omit amounts, counterparties and payloads. Existing rows are imported as explicitly marked baselines, not backdated events.

`business.sql` joins these records with the existing trade, admission and attempt journals. A matched pair has two trade processes, and both can see the shared output evidence. The summary reports one process per admitted trade, not one per pair.

The trade operations home shows where admitted trades are now as a strip of counts in process order, how long trades take for the selected milestone (bucketed relative to its target and coloured by SLA status), and admissions over time stacked by SLA outcome, with bucket width following the time range. Colours are consistent across views: green on time, orange past SLA target, blue in progress, red rejected or failed, purple missing evidence.

The process detail reads left to right. **Process progress** is a milestone strip — received, validated, matched, netted, instructions ready — where each tile shows elapsed time from receipt (`T+`). Green is on time; orange is reached late or still open past its demo SLA target; blue is open within target; red is rejected or failed; purple is missing evidence; grey is not reached or not applicable. **Process timeline** is a horizontal Gantt with one bar per stage — validation, counterparty matching wait, netting and instruction generation — on a time-since-receipt axis that fits the selected trade, independent of the dashboard time range. Dashed reference lines mark the 5 s, 30 s and 60 s targets. Bars are green when on time and orange when the stage's closing milestone is past its target. Each completed bar stops at its committed milestone; the row label carries the exact duration, so millisecond stages stay readable when their bars are thin. Instruction generation starts at the first required output record and can overlap netting; readiness is recorded separately when netting and every required generation record are complete. The timing table gives source timestamps, millisecond intervals, retry counts and readiness time. These intervals include waits and are not CPU time. Only an open bar ends at the last collector observation, with stale evidence marked explicitly. Zero-duration, no-instruction and missing intervals are labelled without fabricated bars. The event list retains per-output generation events and retries; queue attempts and the investigation waterfall provide technical detail.

Missing receipt or baseline history yields an unknown SLA result. Open calculations stop at the collector observation, and stale collection or journal backlog over ten seconds produces Stale. Already recorded completion times remain usable during a collector outage. Partial instruction evidence cannot complete a process. A SENT record without a retained GENERATED event proves current output state but does not establish generation time.

## Retention and checks

The journal history now defaults to 90 days. Set `MOCKNET_BUSINESS_RETENTION_DAYS` before starting the reporter to change it, from 1 to 3650 days. This applies to reporting MQ/business evidence and acknowledged source journals. Latest entity snapshots remain available after detailed history expires. Technical logs and queue samples retain their existing 72-hour policy. Previously expired history cannot be recovered by this change.

Run:

```sh
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-c-business.py
.bootstrap/observability/approach-c/venv/bin/python script/check-mocknet-approach-c.py
```

The business checker rolls back its fixtures. It checks deadlines, counterparty waits, rejection, missing history, stale collection, incomplete outputs, zero-net output, linked legs, retry recovery and the Grafana read-only role. The dashboard checker executes provisioned queries through Grafana.
