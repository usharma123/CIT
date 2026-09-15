# Approach B: strictly JVM runtime metrics

Approach B uses only runtime metrics from OpenTelemetry Java agent 2.26.1. Its three Grafana dashboards are **JVM service overview**, **JVM runtime diagnostics**, and **JVM incident investigation**. The hierarchy follows Approach A, but panels are limited to evidence a JVM can provide.

## Run

Start Approach A's Grafana infrastructure first, then:

```bash
bash script/mocknet-approach-b.sh start
bash script/mocknet-approach-b.sh stream-start
python3 script/mocknet-approach-b-demo.py
python3 script/check-mocknet-approach-b.py
bash script/mocknet-approach-b.sh stream-status
bash script/mocknet-approach-b.sh stream-stop
bash script/mocknet-approach-b.sh stop
```

B runs a separate Java 17 process with a 512 MiB heap limit, 24 consumers, its own PostgreSQL database, collector and Prometheus store. Application port 18091; runtime-only OTLP port 14328; Prometheus port 19091. All host ports bind to loopback. The shared Grafana has a separate B datasource and folder. A continues on port 18081.

Regenerate B dashboards with `python3 script/build-mocknet-approach-b.py`. No dashboard consumes SQL, logs, spans, HTTP metrics, database metrics or custom application metrics. All four selectors are runtime resource labels: environment, service, version and JVM instance. There is deliberately no trade, queue, stage or trace selector because JVM metrics cannot resolve them.

## Enforced signal boundary

- All Java-agent instrumentation defaults to disabled; only `runtime-telemetry` is enabled.
- Trace exporter is `none`, sampling is `always_off`, log exporter is `none`, and metric exemplars are disabled.
- Application component tracing is disabled. Application tracing APIs receive a no-op OpenTelemetry instance.
- Actuator Prometheus export and its HTTP exposure are disabled for B. Its internal application metrics and durable history remain part of the common business build, but are not exported to B monitoring.
- B's collector has only a metrics pipeline. It rejects trace/log endpoints and drops any metric whose name is outside `jvm.*` or whose scope is outside `io.opentelemetry.runtime-telemetry`.
- B's Prometheus scrapes only that collector. The dashboard validator checks every query, live metric family and instrumentation scope. Prometheus adds scrape bookkeeping metrics, but no B panel uses them.

This compares available diagnostic evidence. It is not a performance benchmark between an unmodified application and an instrumented application: both use the same business implementation, retain the durable business/attempt model, and run concurrently on one host.

## What the metrics mean

CPU, heap used/committed/limit, retained heap after GC, GC event duration/count, allocation, platform thread states, buffer memory, file descriptors and loaded classes are observed directly from the runtime. Experimental runtime metrics are enabled and pinned to this agent version. They require compatibility checks before agent upgrades.

Waiting threads may be normal. Rising memory is not proof of a leak. GC duration is not HTTP latency. Low CPU does not show that consumers are progressing. A 30-second collector metric expiry limits retained stale samples; gaps are displayed as gaps, and no missing-data value is converted to zero. Five-second export/scrape and ten-second dashboard refresh are local demonstration settings.

## Incident comparison

The experiment records known workload actions and business outcomes under `.bootstrap/observability/approach-b/incident-results.json`. That ground truth is for evaluating the experiment, not a B dashboard datasource. The workload tests paused consumers, concurrent admissions, business rejection, exhausted retries, database connection pressure and explicit GC. All controls affect only B and expire or are restored by the harness.

JVM metrics can show resource pressure or changes in execution during those intervals. They cannot identify queue depth, a trade's outcome, retry reason, SQL statement, or the critical path of a transaction. Use the measured results in `VALIDATION.md` to distinguish observed changes from assumptions.

## Sources

The configuration follows [OpenTelemetry's instrumentation controls](https://opentelemetry.io/docs/zero-code/java/agent/disable/) and [Java SDK signal/exporter configuration](https://opentelemetry.io/docs/languages/java/configuration/). The runtime signal set is described by [JVM runtime telemetry](https://explorer.opentelemetry.io/java-agent/instrumentation/runtime-telemetry); the running pinned agent's emitted metrics determine the actual panels.
