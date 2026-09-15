# OpenTelemetry review and demo validation

> Historical baseline from the earlier trace-only setup. The L3 implementation and current limits are documented in [README.md](README.md) and [L3-VALIDATION.md](L3-VALIDATION.md).
Reviewed 2026-09-15. Scope: the Java mocknet backend, its tracing aspects and durable queue context, Bootstrap's Java-agent preparation tool, launch scripts, and the local trace-query workflow. This is not a whole-repository security audit.

## Assessment

The existing HTTP-to-queue context handoff is useful and the code already records business outcomes. The largest issues were excessive polling telemetry, unsafe correlation inspection, incomplete W3C handling, and a disposable tracing backend. The new Grafana stack is suitable for demonstrating the pipeline locally. Production rollout still needs the follow-up work below.

## Findings and changes

| Priority | Finding | Resolution |
| --- | --- | --- |
| P1 | `ComponentTracingAspect.collectCorrelation` consumed Iterator arguments and return values. It also recursively traversed collections without limits, allowing tracing to alter results or exhaust the stack. | Iterator and arbitrary Iterable consumption removed. Traversal is bounded to depth 6 and 128 visited values, text parsing to 64 KiB, and component ID values to 256 characters. Metadata extraction skips non-recording spans and ignores runtime extraction failures. Regression tests cover iterators, cycles, and failed metadata reads. |
| P1 | The broad component pointcut and the agent produce many standalone polling traces, even when no requests arrive. The previous `traceview` UI hid polling traces only after storage/export. | Tracing helpers no longer receive component spans. Component services require a valid parent. The new collector keeps whole HTTP, consumer, and error traces, and discards standalone successful polling traces. Agent CPU work remains a follow-up. |
| P2 | Queue context parsing accepted malformed W3C versions/extra fields and did not preserve tracestate. | Both injection and extraction use `W3CTraceContextPropagator`. An additive nullable `traceState` column preserves vendor context. Existing rows without tracestate still work. Tests verify invalid inputs and unsampled parents. |
| P2 | Controller component spans used SERVER kind while the Java agent already creates the HTTP SERVER span. | Component spans now use INTERNAL kind. `QueueMessage.process` remains CONSUMER. |
| P2 | Retry failure reason appeared only on terminal failure, and consumer spans lacked attempt/wait diagnostics. | Every failed/retried consumer now includes the failure reason and retryability. Added `queue.attempt`, `queue.wait_ms`, stage, and messaging attributes. |
| P2 | `script/mocknet-otel.sh` and `src/tool/otel-trace.ts` use latest downloads, ephemeral Jaeger, and a JVM environment inherited by Maven. | Added a separate pinned Grafana/Tempo/Collector launcher, application-only agent attachment, readiness checks, disk-backed exporter queue, and persistent volumes. The legacy Jaeger paths remain unchanged and retain those limitations. |

Source locations:

- [Component tracing](../mocknet/src/main/java/com/cit/mocknet/config/ComponentTracingAspect.java)
- [Queue context injection](../mocknet/src/main/java/com/cit/mocknet/queue/QueueBroker.java)
- [Consumer tracing](../mocknet/src/main/java/com/cit/mocknet/queue/QueueMessageTracing.java)
- [Persistent queue model](../mocknet/src/main/java/com/cit/mocknet/model/QueueMessage.java)
- [Collector configuration](collector.yaml)
- [New launcher](../script/mocknet-grafana.sh)

## Measured effect

With the backend idle for a 20-second measurement window, the collector accepted 18,944 spans, discarded 8,558 traces, retained zero traces, and exported zero spans to Tempo. These are collector counter deltas from `.bootstrap/observability/idle-before.prom` and `idle-after.prom`.

This demonstrates removal of idle export/storage noise in that window. It is not a throughput benchmark or a before/after measurement of application CPU or latency. The agent still records approximately 947 spans per second in that observed idle window before the collector filters them.

## Validation

- `mvn -q -f mocknet/pom.xml package`: 26 tests passed, including existing end-to-end/concurrency coverage and the new tracing regressions.
- `bun test test/tool/otel-trace.test.ts test/tool/traceview.test.ts`: 5 tests passed, 45 assertions.
- `bun run typecheck`: fails in unchanged existing TypeScript code, including TUI, Electron, provider, configuration, and tool files. Full output is `.bootstrap/observability/typecheck.log`. No TypeScript implementation files were changed for this work.
- Shell syntax, Python syntax, dashboard JSON, and `git diff --check` validated.
- Live demo: 15 submissions across the scenarios in [README](README.md), with current-run business state and trace ID assertions. Six TraceQL searches verify errors, rejections, retries, slow processing, settlement, and business ID lookup.
- Resilience test: stopped Tempo, submitted a trade, observed one buffered export batch, restarted Tempo, then retrieved both the new trace and a pre-outage trace. Evidence: `.bootstrap/observability/resilience-results.json`.
- Browser inspection verified populated dashboard tables and a working Explore waterfall with HTTP, queue consumer, component, and SQL spans.

Live results and raw traces are in `.bootstrap/observability`. The generated `DEMO-RETRY-*` and `DEMO-SLOW-*` scenarios exercise an explicit demo-only fault aspect. They are controlled failures, not evidence of naturally occurring database contention or latency.

## Recommended next work

1. Reduce instrumentation at the source. There are duplicate custom repository, Spring Data, Hibernate, and JDBC spans. Choose a detail level per environment, retain boundary/component spans that answer questions, and benchmark under representative load. Collector filtering alone cannot recover agent CPU.
2. Replace reflection/payload scanning with explicit business correlation at ingestion and queue publication. Carry business trade/message IDs in a versioned queue envelope. Match/netting stages often carry numeric record IDs, so not every span has a business trade ID.
3. Link both incoming trades when they merge into a matched trade. The current netting trace follows the request that triggers matching; the other request's trace is not represented by an explicit span link.
4. Add application/queue metrics and a metrics backend before presenting rates, backlog trends, error percentages, or p95 latency. Trace search tables and sampled traces are insufficient for these aggregates.
5. For production, use authenticated Grafana, object storage, capacity limits, redaction rules, and trace-aware collector routing. Tail sampling requires all spans for a trace to reach the same collector. Its ten-second pending window is memory-resident, and very late spans require an explicit retention/sampling design.
6. The Bootstrap TypeScript CLI's AI SDK telemetry flag in `src/session/llm.ts` is separate. No CLI OpenTelemetry SDK/export pipeline is provisioned by this change. Instrument it deliberately if CLI/provider traces should join backend traces.

## Sources

- [OpenTelemetry Java SDK and propagators](https://opentelemetry.io/docs/languages/java/sdk/)
- [Collector tail sampling, pinned version](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/v0.147.0/processor/tailsamplingprocessor)
- [Grafana trace query editor and search semantics](https://grafana.com/docs/grafana/latest/datasources/tempo/query-editor/)
