# Live validation results

> Historical baseline from the earlier trace-only setup. The L3 implementation and current limits are documented in [README.md](README.md) and [L3-VALIDATION.md](L3-VALIDATION.md).
Final demo run: `20260915T130637-9dc3`. All four services returned HTTP 200 at the final readiness check.

[Open this run in Grafana](http://localhost:3300/d/mocknet-traces?from=1789477596000&to=now)

| Scenario | Ingestion state | Recorded failures before completion | Spans | Trace ID |
| --- | --- | ---: | ---: | --- |
| matched-buy | DONE | 0 | 50 | `74d9b566ee91cefdabb3b47c54bb8f37` |
| matched-sell | DONE | 0 | 192 | `c2b5c000f7e55feb116d1581eaf44504` |
| rejected | DONE | 0 | 26 | `a120915343c8b60dfde890030ceabfae` |
| malformed | FAILED | 1 | 21 | `49cb65e9a79c30710ef0d421c4daec27` |
| missing-id | FAILED | 1 | 22 | `5467e13e0f967e1f164b345802146ad1` |
| unmatched | DONE | 0 | 50 | `d321469f29fb797b1b4ab2df85404ff6` |
| slow | DONE | 0 | 50 | `e86fcb8dc19ade282b9ed96a64a8652f` |
| retry-recovery | DONE | 2 | 66 | `3023ea7d4389a0b2c2c2b8e08e328af4` |
| retry-exhaustion | FAILED | 3 | 36 | `ea14be57861fc4eb570eb0b1bce030ce` |
| concurrent-0 | DONE | 0 | 50 | `572bea1322990d3349c6d0f97b41ea63` |
| concurrent-1 | DONE | 0 | 50 | `7929eaa2952e1e798cc10663c30d6d76` |
| concurrent-2 | DONE | 0 | 50 | `0fb7782049f79010835d8adf748f4121` |
| concurrent-3 | DONE | 0 | 50 | `4516bfc25a4cd90146fe426f91e52153` |
| concurrent-4 | DONE | 0 | 50 | `6e69af4c5e0b97ec04f9eab231110898` |
| concurrent-5 | DONE | 0 | 50 | `3331654ec0050e8369a2512b25d92a36` |

The attempts column uses the persisted queue failure counter. Consumer span `queue.attempt` is one-based. `DONE` describes queue processing, not external financial settlement.

## TraceQL checks

| Query purpose | Current-run matches |
| --- | ---: |
| errors | 4 |
| rejections | 1 |
| retries | 2 |
| slow | 1 |
| settlement | 1 |
| business-id | 2 |

## Persistence and buffering

Tempo was stopped while a real submission was processed. The collector held 1 export batch on its disk-backed queue. After restart, trace `abaaeba2c196d4641b5caff3732574e6` was retrieved with a consumer span. Earlier trace `8c1b8d3f63675cddc55a5903bd3839c9` was also retrieved.

## Idle trace filtering

Over 20 seconds, 18,944 spans entered the collector, 8,558 traces were discarded, and 0 spans were exported. This is a storage/export result, not an application CPU benchmark.

## Artifacts

- `.bootstrap/observability/demo-results.json`
- `.bootstrap/observability/trace-*.json`
- `.bootstrap/observability/resilience-results.json`
- `.bootstrap/observability/idle-before.prom` and `idle-after.prom`
- `.bootstrap/observability/maven-test.log`, `bun-test.log`, and `typecheck.log`

Older exploratory submissions remain in the isolated database and Tempo store. Use the run-specific dashboard link above to focus on the final validation. Traces expire after 72 hours.
