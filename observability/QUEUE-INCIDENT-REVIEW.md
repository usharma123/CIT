# Queue incident review

Reviewed on 2026-09-15 against the running Mocknet demonstration and primary documentation.

## Assessment

The useful pattern is service impact → queue behavior → individual operation → trace and logs. Grafana queries metrics and read-only diagnostic metadata. It must not consume queue messages to display them.

This service uses PostgreSQL as its durable queue. The queue tables and application metrics are therefore its queue sources. A production RabbitMQ, Kafka or IBM MQ deployment needs its own broker/consumer exporter and broker-specific semantics. The current dashboard is not proof of integration with those brokers.

[Grafana's dashboard guidance](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/) recommends focused questions, a service hierarchy, drilldowns and low cognitive load. [RabbitMQ's monitoring guide](https://www.rabbitmq.com/docs/monitoring) recommends Prometheus/Grafana, application metrics alongside broker metrics, and controlling monitoring overhead. These principles support the changes below; the exact panel choices are our implementation decisions.

## Questions the dashboard now answers

| Incident question | Evidence |
| --- | --- |
| Which work is affected now? | Current unfinished operations remain visible even when admitted before the selected incident interval. An explicit operation ID searches all admissions. |
| Which queue is building up? | Separate ready, scheduled retry, processing and parked dead-letter counts; oldest-ready and longest-processing ages. All five queues remain visible when empty. |
| Are consumers keeping up? | Arrivals and terminal departures per minute, with successful, rejected and failed departures separate. Retry attempts do not count as departures. |
| Is time spent waiting or executing? | Ready-wait p95, oldest still-ready age, handler p95, database waiters and SQL spans. A never-claimed message has no consumer-span latency sample. |
| What failed and where? | Current message list includes the source stage of a dead letter, original failure count, last failure reason and worker. Details opens the operation from admission through now. |
| Did retries recover? | Retry share of finished attempts plus durable attempt outcomes. Attempt failures and failed business operations have different denominators. |
| Can the displayed evidence be trusted? | Current SQL reads are distinct from historical metric snapshots. Stale snapshots do not fire queue-age alerts; missing snapshots have a separate alert. Scrape failure is labelled as telemetry failure, not proof of business outage. |

The overview still has seven panels and no stat cards. Queue detail stays in stage diagnostics. Current queues are global to the one shared database. Version/instance filters select handler telemetry; they do not imply queue ownership. Shared queue snapshots use `max` across app exporters rather than adding duplicate copies. Independent databases would require a database/cluster label before aggregation.

[OpenTelemetry messaging conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/) distinguish message creation and consumer processing. Their correlation explains a message's path; traces do not establish a complete queue inventory. [Google SRE's golden signals](https://sre.google/sre-book/monitoring-distributed-systems/) support looking at traffic, latency, errors and saturation together. Queue depth by itself cannot establish customer impact.

## Live validation

The streaming feed continued at one admission per second during validation.

- Paused ingestion through the authenticated demo control and admitted a unique probe. Both current-operation and queue-list queries found it while using an earlier historical interval. There were zero consumer attempts at that point.
- The backlog alert fired. The browser showed 30 ready ingestion messages with an oldest ready age of 29.5 seconds, alongside empty matching/netting queues and parked dead letters.
- Resumed ingestion. The probe completed ingestion after 30.315 seconds waiting and 0.012 seconds processing, then reached AWAITING_COUNTERPARTY. This correctly identifies consumer delay rather than a slow handler.
- Browser drilldown from a dead letter opened the correct operation ID and an interval beginning before its admission. Failure count reflects the failed source message, not the fresh DLQ record.
- All 39 dashboard queries executed through Grafana without errors. All eight alert rules passed `promtool` syntax validation and reported healthy evaluation. Panel layout has no overlapping panels.

Evidence: `.bootstrap/observability/queue-incident-validation.json` and `queue-review-check.json`. Time-window indexes are created concurrently; application processing was not restarted for this review.

## What still prevents a production-readiness claim

1. **Business deadlines and routing:** choose the actual admission-to-completion objective, counterparty-wait policy, severity, owner and paging destination. Current 10-second queue thresholds are demonstration values. Persisted unmatched trades are business waiting, not a consumer queue backlog. Real SLO alerts must cover that distinction.
2. **Broker and worker truth:** this is one JVM and one PostgreSQL database. The heartbeat is the most recent poll from any worker in a stage, not a live count of every configured worker. The separate SETTLEMENT queue is unused by normal processing. External settlement is not observed.
3. **Monitoring scale and failure isolation:** current read-only support views use the transactional database with a five-second statement timeout and four Grafana connections. At production retention and concurrency, benchmark query plans and use broker-exported counters or a reporting projection for aggregate history. A database outage also removes this SQL evidence. Production retention, independent monitoring, HA and paging delivery need deployment testing.
4. **Safe remediation:** Grafana has no replay/delete controls. Before replay, investigate committed business side effects and deduplication. Queue lease fencing and matching concurrency have the limits documented in the main runbook. SSO/RBAC/TLS and an audited remediation workflow remain deployment requirements.

This is an incident-tested local support dashboard. These remaining requirements cannot be certified by adding more panels.
