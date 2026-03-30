# OTEL Tracing Workflow for Mocknet

How to run traces, query them, and verify end-to-end distributed tracing across mocknet's queue-based pipeline.

OTEL is already set up in this repo. This doc covers how to use it.

## How It Works

OTEL is a runtime harness inside the JVM. As each method fires, it creates a **span** (timing + attributes + parent reference) and ships it to Jaeger. Jaeger receives individual spans, groups them by trace ID, and renders the waterfall.

OTEL doesn't send complete traces — it sends **individual spans as they finish**. Each span carries the trace ID and its parent span ID. Jaeger assembles the tree.

Trace context propagates across async queue boundaries via a `traceContext` field on `QueueMessage` (W3C traceparent format). Without this, each queue stage would start a new trace.

## Start Tracing

```bash
# Start Jaeger + mocknet with OTEL Java agent
script/mocknet-otel.sh start

# Check status
script/mocknet-otel.sh status

# Tail logs
script/mocknet-otel.sh logs

# Stop everything
script/mocknet-otel.sh stop
```

Jaeger UI: http://localhost:16686
Jaeger OTLP endpoint: http://localhost:4318

## Submit Test Trades

Submit a matching pair to trigger the full pipeline (ingestion -> matching -> netting -> settlement):

```bash
# Trade 1: BANK_A buys USD from BANK_B
curl -s -w "\nHTTP %{http_code}" -X POST http://localhost:8080/api/trades \
  -H 'Content-Type: application/xml' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<tradeMessage>
  <header>
    <messageId>MSG-TEST-001</messageId>
    <creationTimestamp>2026-03-26T10:00:00Z</creationTimestamp>
  </header>
  <trade>
    <tradeId>TRD-TEST-001</tradeId>
    <tradeType>SPOT</tradeType>
    <party1><partyId>BANK_A</partyId><role>BUYER</role></party1>
    <party2><partyId>BANK_B</partyId><role>SELLER</role></party2>
    <currencyPair>
      <currency1>USD</currency1><amount1>1000000.00</amount1>
      <currency2>EUR</currency2><amount2>920000.00</amount2>
      <exchangeRate>1.0869565</exchangeRate>
    </currencyPair>
    <valueDate>2026-04-01</valueDate>
  </trade>
</tradeMessage>'

# Trade 2: Mirror trade (BANK_B buys EUR from BANK_A)
curl -s -w "\nHTTP %{http_code}" -X POST http://localhost:8080/api/trades \
  -H 'Content-Type: application/xml' \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<tradeMessage>
  <header>
    <messageId>MSG-TEST-002</messageId>
    <creationTimestamp>2026-03-26T10:00:01Z</creationTimestamp>
  </header>
  <trade>
    <tradeId>TRD-TEST-002</tradeId>
    <tradeType>SPOT</tradeType>
    <party1><partyId>BANK_B</partyId><role>BUYER</role></party1>
    <party2><partyId>BANK_A</partyId><role>SELLER</role></party2>
    <currencyPair>
      <currency1>EUR</currency1><amount1>920000.00</amount1>
      <currency2>USD</currency2><amount2>1000000.00</amount2>
      <exchangeRate>0.92</exchangeRate>
    </currencyPair>
    <valueDate>2026-04-01</valueDate>
  </trade>
</tradeMessage>'
```

Both should return HTTP 202. Wait ~5 seconds for the full pipeline to process.

## Query Traces from Jaeger

### List recent traces

```bash
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=20&lookback=1h' \
  | python3 -m json.tool
```

### Find traces by stage

Replace the tag value to search by stage (`INGESTION`, `MATCHING`, `NETTING`, `SETTLEMENT`):

```bash
# Ingestion traces
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22cls.stage%22%3A%22INGESTION%22%7D'

# Matching traces
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22cls.stage%22%3A%22MATCHING%22%7D'

# Netting traces
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22cls.stage%22%3A%22NETTING%22%7D'
```

The `tags` param is URL-encoded JSON. `%7B%22cls.stage%22%3A%22INGESTION%22%7D` decodes to `{"cls.stage":"INGESTION"}`.

### Find traces by correlation ID

```bash
# By message ID
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22message.id%22%3A%22MSG-TEST-001%22%7D'

# By trade ID
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22trade.id%22%3A%22TRD-TEST-001%22%7D'

# By matched trade ID
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h&tags=%7B%22matched.trade.id%22%3A%22650%22%7D'
```

### Fetch a specific trace by ID

```bash
curl -s 'http://localhost:16686/api/traces/TRACE_ID_HERE' | python3 -m json.tool
```

### Summarize traces (span count, stages, correlations)

Pipe any trace list query into this Python snippet to get a readable summary:

```bash
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=10&lookback=1h' | python3 -c "
import json, sys
data = json.load(sys.stdin)
for t in data.get('data', []):
    spans = t['spans']
    stages = set()
    corrs = {}
    for s in spans:
        tags = {tag['key']: tag['value'] for tag in s['tags']}
        if 'cls.stage' in tags: stages.add(tags['cls.stage'])
        for k in ['trade.id','message.id','matched.trade.id','netting.set.id']:
            if k in tags: corrs[k] = tags[k]
    print(f'traceID: {t[\"traceID\"]}  spans: {len(spans)}  stages: {sorted(stages)}  correlations: {corrs}')
"
```

### Print a trace as a waterfall tree

Replace the trace ID in the URL:

```bash
curl -s 'http://localhost:16686/api/traces/TRACE_ID_HERE' | python3 -c "
import json, sys

data = json.load(sys.stdin)
trace = data['data'][0]
spans = trace['spans']
span_map = {s['spanID']: s for s in spans}
children = {}
roots = []

for s in spans:
    parent_id = None
    for ref in s.get('references', []):
        if ref['refType'] == 'CHILD_OF':
            parent_id = ref['spanID']
    if parent_id and parent_id in span_map:
        children.setdefault(parent_id, []).append(s)
    else:
        roots.append(s)

def print_tree(span, depth=0):
    tags = {t['key']: t['value'] for t in span['tags']}
    stage = tags.get('cls.stage', '')
    thread = tags.get('thread.name', '')
    dur_ms = span['duration'] / 1000
    corr = []
    for k in ['trade.id','message.id','matched.trade.id','netting.set.id','queue.name','processing.outcome']:
        if k in tags: corr.append(f'{k}={tags[k]}')
    corr_str = '  [' + ', '.join(corr) + ']' if corr else ''
    indent = '  ' * depth
    op = span['operationName'][:50]
    print(f'{indent}{op:<50} {stage:<12} {dur_ms:>7.1f}ms  {thread}{corr_str}')
    kids = children.get(span['spanID'], [])
    kids.sort(key=lambda s: s['startTime'])
    for kid in kids:
        print_tree(kid, depth + 1)

roots.sort(key=lambda s: s['startTime'])
for r in roots:
    print_tree(r)
"
```

### Print a flat timeline (no tree, sorted by time)

```bash
curl -s 'http://localhost:16686/api/traces/TRACE_ID_HERE' | python3 -c "
import json, sys

data = json.load(sys.stdin)
trace = data['data'][0]
spans = sorted(trace['spans'], key=lambda s: s['startTime'])

for s in spans:
    tags = {t['key']: t['value'] for t in s['tags']}
    stage = tags.get('cls.stage', tags.get('db.operation', ''))
    kind = tags.get('component.kind', tags.get('span.kind', ''))
    thread = tags.get('thread.name', '')
    corr = []
    for k in ['trade.id','message.id','matched.trade.id','netting.set.id','queue.name','processing.outcome']:
        if k in tags: corr.append(f'{k}={tags[k]}')
    corr_str = '  [' + ', '.join(corr) + ']' if corr else ''
    dur_ms = s['duration'] / 1000
    print(f'{s[\"operationName\"]:<55} {stage:<12} {kind:<12} {thread:<22} {dur_ms:>8.1f}ms{corr_str}')
"
```

## Verify End-to-End Propagation

A correctly propagated trace has **one root span** and spans across **all stages** (`HTTP`, `INGESTION`, `MATCHING`, `NETTING`, `SETTLEMENT`). If you see multiple root spans or stages missing, context propagation is broken.

Quick check after submitting a trade pair:

```bash
# Find traces that hit INGESTION stage and check if they also contain other stages
curl -s 'http://localhost:16686/api/traces?service=mocknet&limit=5&lookback=1h&tags=%7B%22cls.stage%22%3A%22INGESTION%22%7D' | python3 -c "
import json, sys
data = json.load(sys.stdin)
for t in data.get('data', []):
    spans = t['spans']
    stages = set()
    for s in spans:
        tags = {tag['key']: tag['value'] for tag in s['tags']}
        if 'cls.stage' in tags: stages.add(tags['cls.stage'])
    full = 'INGESTION' in stages and 'MATCHING' in stages and 'NETTING' in stages
    status = 'FULL E2E' if full else 'PARTIAL'
    print(f'{status}  traceID: {t[\"traceID\"]}  spans: {len(spans)}  stages: {sorted(stages)}')
"
```

Expected output for a matched trade pair:
```
FULL E2E  traceID: 1144ddaf...  spans: 195  stages: ['DATABASE', 'HTTP', 'INGESTION', 'MATCHING', 'NETTING', 'OTHER', 'SETTLEMENT']
```

## Check System Status

```bash
curl -s http://localhost:8080/api/status | python3 -m json.tool
```

Returns trade counts by status, queue depths, and 2PC transaction states.

## Span Attributes Reference

These are the attributes you can search/filter by in Jaeger:

| Attribute | Description | Example Values |
|-----------|-------------|----------------|
| `cls.stage` | Processing stage | `HTTP`, `INGESTION`, `MATCHING`, `NETTING`, `SETTLEMENT`, `DATABASE`, `OTHER` |
| `trade.id` | Trade business ID | `TRD-TEST-001` |
| `trade.record.id` | Trade DB primary key | `3550` |
| `message.id` | FpML message ID | `MSG-TEST-001` |
| `matched.trade.id` | Matched trade DB ID | `650` |
| `netting.set.id` | Netting set DB ID | `1139` |
| `queue.name` | Queue name | `INGESTION`, `MATCHING`, `NETTING`, `SETTLEMENT`, `DEAD_LETTER` |
| `processing.outcome` | Result of queue processing | `completed`, `rejected`, `retried`, `failed` |
| `component.class` | Java class name | `TradeMatchingEngine` |
| `component.kind` | Component type | `controller`, `service`, `repository` |
| `component.method` | Method name | `processMatchingMessage` |
| `worker.name` | Thread that processed | `ingestion-worker-3` |
| `failure.reason_code` | Why it failed | Failure reason enum value |

## Message Schema at Each Queue Boundary

| Queue | Payload entering the consumer | Parser class |
|-------|-------------------------------|--------------|
| `INGESTION` | Raw FpML XML (`<tradeMessage>...</tradeMessage>`) | `TradeXmlParser` |
| `MATCHING` | `{"tradeId": <long>}` | `MatchingMessageParser` |
| `NETTING` | `{"matchedTradeId": <long>}` | `NettingMessageParser` |
| `SETTLEMENT` | `{"nettingSetIds": [<long>, ...]}` | `SettlementMessageParser` |
| `DEAD_LETTER` | `{"originalQueue": "...", "originalPayload": "...", "attempts": N, "workerName": "...", "reasonCode": "...", "errorMessage": "...", "failedAt": "...", "retryable": bool}` | N/A |

## Debugging Tips

- **Fragmented traces (separate traceID per stage)**: `traceContext` field is null when consumed. Check that `captureTraceContext()` in `QueueBroker.publish()` runs within an active span scope, and that `extractParentContext()` in `QueueMessageTracing.startProcessingSpan()` is called before `setNoParent()`.
- **Missing spans for a component**: That consumer loop doesn't have the span wrapper. Grep for `claimNext` and verify every call site creates a span via `queueMessageTracing.startProcessingSpan()`.
- **No traces appearing in Jaeger**: Check that the OTEL agent is attached (`JAVA_TOOL_OPTIONS`), Jaeger is running on port 4318, and `OTEL_TRACES_EXPORTER=otlp` is set. Run `script/mocknet-otel.sh status` to verify.
- **Spans appear but no correlation IDs**: The AOP aspect (`ComponentTracingAspect`) isn't extracting from your domain objects. Check its correlation extraction logic.

## Key Files

| File | Role |
|------|------|
| `script/mocknet-otel.sh` | Startup script — Jaeger + OTEL agent + app lifecycle |
| `config/TracingConfiguration.java` | Spring bean for `OpenTelemetry` |
| `config/ComponentTracingAspect.java` | AOP aspect — auto-traces every public method with stage + correlation attributes |
| `queue/QueueMessageTracing.java` | Queue consumer span creation + W3C traceparent context propagation |
| `queue/QueueBroker.java` | `captureTraceContext()` — injects current span context into published messages |
| `model/QueueMessage.java` | `traceContext` field — carries W3C traceparent across queue boundaries |
