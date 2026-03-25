import path from "path"

export const CLS_STAGE_ORDER = [
  "HTTP",
  "INGESTION",
  "MATCHING",
  "NETTING",
  "SETTLEMENT",
  "DATABASE",
  "OTHER",
] as const

export type ClsStageName = (typeof CLS_STAGE_ORDER)[number]
export type TraceStatus = "ok" | "error" | "partial"

export interface TraceViewParams {
  jaegerBaseUrl: string
  serviceName: string
  lookbackMinutes: number
  limit: number
  traceId?: string
  tradeId?: string
  messageId?: string
}

type JaegerValue = string | number | boolean | null

export interface JaegerTag {
  key: string
  value: JaegerValue
}

export interface JaegerReference {
  refType?: string
  spanID?: string
  spanId?: string
}

export interface JaegerSpan {
  traceID?: string
  traceId?: string
  spanID?: string
  spanId?: string
  operationName?: string
  processID?: string
  processId?: string
  startTime?: number
  duration?: number
  tags?: JaegerTag[]
  references?: JaegerReference[]
}

export interface JaegerProcess {
  serviceName?: string
  tags?: JaegerTag[]
}

export interface JaegerTrace {
  traceID?: string
  traceId?: string
  spans?: JaegerSpan[]
  processes?: Record<string, JaegerProcess>
}

interface JaegerResponse<T> {
  data?: T
}

export interface TraceStage {
  name: ClsStageName
  status: TraceStatus
  startTime: number
  durationMs: number
  inferred: boolean
  explicit: boolean
  spanCount: number
  serviceNames: string[]
  operationNames: string[]
  attributes: Record<string, string>
}

export interface TraceSpan {
  traceId: string
  spanId: string
  parentSpanId?: string
  operationName: string
  serviceName: string
  startTime: number
  durationMs: number
  stage: ClsStageName
  inferredStage: boolean
  status: TraceStatus
  tags: Record<string, string>
}

export interface NormalizedTrace {
  traceId: string
  rootSpan: string
  startTime: number
  durationMs: number
  status: TraceStatus
  tradeId?: string
  messageId?: string
  stages: TraceStage[]
  rawSpans: TraceSpan[]
  inferred: boolean
}

export interface TraceFlow extends NormalizedTrace {
  groupKey: string
  grouping: "tradeId" | "messageId" | "traceId"
  traceIds: string[]
  relatedTraceIds: string[]
  attempts: number
}

export interface TraceViewData {
  service: string
  jaegerBaseUrl: string
  generatedAt: string
  flows: TraceFlow[]
  traces: NormalizedTrace[]
}

const TRADE_ID_KEYS = ["trade.id", "tradeId", "trade_id", "cls.trade.id"]
const MESSAGE_ID_KEYS = ["message.id", "messageId", "message_id", "cls.message.id"]
const EXPLICIT_STAGE_KEYS = ["component.stage", "cls.stage", "stage"]
const QUEUE_NAME_KEYS = ["queue.name", "messaging.destination.name", "messaging.destination", "queue"]
const LOW_SIGNAL_ROOT_PATTERNS = [
  "queuebroker.claimnext",
  "queuemessagerepository.findclaimablenewids",
  "queuemessagerepository.findstaleprocessingids",
]
const LOW_SIGNAL_OPERATION_PATTERNS = [
  "queuebroker.claimnext",
  "queuebroker.getpollinterval",
  "queuemessagerepository.findclaimablenewids",
  "queuemessagerepository.findstaleprocessingids",
  "select ./data/coredb.queue_messages",
  "select com.cit.clsnet.model.queuemessage",
]

export async function fetchTraceViewData(params: TraceViewParams): Promise<TraceViewData> {
  const traces = await fetchJaegerTraces(params)
  const normalizedTraces = traces.map(normalizeTrace).filter((trace): trace is NormalizedTrace => Boolean(trace))
  const filteredTraces = normalizedTraces
    .filter((trace) => matchesFilters(trace, params))
    .filter((trace) => shouldIncludeTrace(trace, params))
  const flows = groupNormalizedTraces(filteredTraces)

  return {
    service: params.serviceName,
    jaegerBaseUrl: params.jaegerBaseUrl,
    generatedAt: new Date().toISOString(),
    flows,
    traces: filteredTraces.sort(compareTraces),
  }
}

async function fetchJaegerTraces(params: TraceViewParams): Promise<JaegerTrace[]> {
  if (params.traceId) {
    const trace = await fetchTraceById(params.jaegerBaseUrl, params.traceId)
    return trace ? [trace] : []
  }

  const endMicros = Date.now() * 1000
  const startMicros = endMicros - params.lookbackMinutes * 60 * 1000 * 1000
  const searchUrl = new URL("/api/traces", params.jaegerBaseUrl)
  searchUrl.searchParams.set("service", params.serviceName)
  searchUrl.searchParams.set("lookback", "custom")
  searchUrl.searchParams.set("start", String(startMicros))
  searchUrl.searchParams.set("end", String(endMicros))
  searchUrl.searchParams.set("limit", String(params.limit))

  const response = await fetch(searchUrl)
  if (!response.ok) {
    throw new Error(`Failed to query Jaeger search API: ${response.status} ${response.statusText}`)
  }

  const body = (await response.json()) as JaegerResponse<JaegerTrace[]>
  const searchEntries = Array.isArray(body.data) ? body.data : []
  const traceIds = unique(searchEntries.map(extractTraceId).filter(Boolean))
  const entryMap = new Map(searchEntries.map((entry) => [extractTraceId(entry), entry]))

  const traces = await Promise.all(
    traceIds.map(async (traceId) => {
      const detailed = await fetchTraceById(params.jaegerBaseUrl, traceId)
      return detailed ?? entryMap.get(traceId) ?? null
    }),
  )

  return traces.filter((trace): trace is JaegerTrace => Boolean(trace))
}

async function fetchTraceById(jaegerBaseUrl: string, traceId: string): Promise<JaegerTrace | null> {
  const url = new URL(`/api/traces/${traceId}`, jaegerBaseUrl)
  const response = await fetch(url)
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`Failed to fetch Jaeger trace ${traceId}: ${response.status} ${response.statusText}`)
  }

  const body = (await response.json()) as JaegerResponse<JaegerTrace[] | JaegerTrace>
  if (Array.isArray(body.data)) {
    return body.data[0] ?? null
  }
  return body.data ?? null
}

export function normalizeTrace(trace: JaegerTrace): NormalizedTrace | null {
  const traceId = extractTraceId(trace)
  const spans = Array.isArray(trace.spans) ? trace.spans : []
  if (!traceId || spans.length === 0) {
    return null
  }

  const processes = trace.processes ?? {}
  const normalizedSpans = spans
    .map((span) => normalizeSpan(traceId, span, processes))
    .filter((span): span is TraceSpan => Boolean(span))
    .sort((a, b) => a.startTime - b.startTime)

  if (normalizedSpans.length === 0) {
    return null
  }

  const stageMap = new Map<ClsStageName, TraceSpan[]>()
  for (const span of normalizedSpans) {
    const existing = stageMap.get(span.stage) ?? []
    existing.push(span)
    stageMap.set(span.stage, existing)
  }

  const stages = Array.from(stageMap.entries())
    .map(([name, spansForStage]) => summarizeStage(name, spansForStage))
    .sort((a, b) => {
      const orderDiff = CLS_STAGE_ORDER.indexOf(a.name) - CLS_STAGE_ORDER.indexOf(b.name)
      if (orderDiff !== 0) return orderDiff
      return a.startTime - b.startTime
    })

  const tradeId = firstNonEmpty(normalizedSpans.flatMap((span) => TRADE_ID_KEYS.map((key) => span.tags[key])))
  const messageId = firstNonEmpty(normalizedSpans.flatMap((span) => MESSAGE_ID_KEYS.map((key) => span.tags[key])))
  const rootSpan = findRootSpan(normalizedSpans)
  const startTime = normalizedSpans[0].startTime
  const endTime = normalizedSpans.reduce((max, span) => Math.max(max, span.startTime + span.durationMs), startTime)
  const hasErrors = stages.some((stage) => stage.status === "error")
  const hasExplicitClsHints = normalizedSpans.some(
    (span) =>
      EXPLICIT_STAGE_KEYS.some((key) => key in span.tags) ||
      QUEUE_NAME_KEYS.some((key) => key in span.tags) ||
      TRADE_ID_KEYS.some((key) => key in span.tags) ||
      MESSAGE_ID_KEYS.some((key) => key in span.tags),
  )

  return {
    traceId,
    rootSpan,
    startTime,
    durationMs: Math.max(1, endTime - startTime),
    status: hasErrors ? "error" : hasExplicitClsHints ? "ok" : "partial",
    tradeId,
    messageId,
    stages,
    rawSpans: normalizedSpans,
    inferred: !hasExplicitClsHints || stages.some((stage) => stage.inferred),
  }
}

function normalizeSpan(
  traceId: string,
  span: JaegerSpan,
  processes: Record<string, JaegerProcess>,
): TraceSpan | null {
  const spanId = span.spanID ?? span.spanId
  if (!spanId || typeof span.startTime !== "number" || typeof span.duration !== "number") {
    return null
  }

  const process = processes[span.processID ?? span.processId ?? ""] ?? {}
  const tags = flattenTags([...(process.tags ?? []), ...(span.tags ?? [])])
  const operationName = span.operationName ?? "unnamed span"
  const serviceName = process.serviceName ?? "unknown-service"
  const stageInfo = inferStage(operationName, serviceName, tags)
  const childOf = span.references?.find((ref) => (ref.refType ?? "").toUpperCase() === "CHILD_OF")

  return {
    traceId,
    spanId,
    parentSpanId: childOf?.spanID ?? childOf?.spanId,
    operationName,
    serviceName,
    startTime: toEpochMs(span.startTime),
    durationMs: toDurationMs(span.duration),
    stage: stageInfo.stage,
    inferredStage: stageInfo.inferred,
    status: isErrorSpan(tags) ? "error" : stageInfo.inferred ? "partial" : "ok",
    tags,
  }
}

function summarizeStage(name: ClsStageName, spans: TraceSpan[]): TraceStage {
  const sorted = [...spans].sort((a, b) => a.startTime - b.startTime)
  const first = sorted[0]
  const end = sorted.reduce((max, span) => Math.max(max, span.startTime + span.durationMs), first.startTime)
  const tradeId = firstNonEmpty(sorted.flatMap((span) => TRADE_ID_KEYS.map((key) => span.tags[key])))
  const messageId = firstNonEmpty(sorted.flatMap((span) => MESSAGE_ID_KEYS.map((key) => span.tags[key])))
  const queueName = firstNonEmpty(sorted.flatMap((span) => QUEUE_NAME_KEYS.map((key) => span.tags[key])))

  return {
    name,
    status: sorted.some((span) => span.status === "error")
      ? "error"
      : sorted.every((span) => span.inferredStage)
        ? "partial"
        : "ok",
    startTime: first.startTime,
    durationMs: Math.max(1, end - first.startTime),
    inferred: sorted.every((span) => span.inferredStage),
    explicit: sorted.some((span) => !span.inferredStage),
    spanCount: sorted.length,
    serviceNames: unique(sorted.map((span) => span.serviceName)),
    operationNames: unique(sorted.map((span) => span.operationName)),
    attributes: {
      ...(queueName ? { queueName } : {}),
      ...(tradeId ? { tradeId } : {}),
      ...(messageId ? { messageId } : {}),
    },
  }
}

function groupNormalizedTraces(traces: NormalizedTrace[]): TraceFlow[] {
  const grouped = new Map<string, TraceFlow>()

  for (const trace of [...traces].sort(compareTraces)) {
    const grouping = trace.tradeId ? "tradeId" : trace.messageId ? "messageId" : "traceId"
    const groupKey = trace.tradeId ?? trace.messageId ?? trace.traceId
    const existing = grouped.get(groupKey)

    if (!existing) {
      grouped.set(groupKey, {
        ...trace,
        groupKey,
        grouping,
        traceIds: [trace.traceId],
        relatedTraceIds: [],
        attempts: 1,
      })
      continue
    }

    const allTraceIds = unique([trace.traceId, ...existing.traceIds])
    grouped.set(
      groupKey,
      trace.startTime > existing.startTime
        ? {
            ...trace,
            groupKey,
            grouping,
            traceIds: allTraceIds,
            relatedTraceIds: unique(existing.traceIds),
            attempts: existing.attempts + 1,
          }
        : {
            ...existing,
            traceIds: allTraceIds,
            relatedTraceIds: unique([trace.traceId, ...existing.relatedTraceIds]),
            attempts: existing.attempts + 1,
          },
    )
  }

  return [...grouped.values()].sort(compareTraces)
}

function matchesFilters(trace: NormalizedTrace, params: Pick<TraceViewParams, "traceId" | "tradeId" | "messageId">) {
  if (params.traceId && trace.traceId !== params.traceId) return false
  if (params.tradeId && trace.tradeId !== params.tradeId) return false
  if (params.messageId && trace.messageId !== params.messageId) return false
  return true
}

function shouldIncludeTrace(
  trace: NormalizedTrace,
  params: Pick<TraceViewParams, "traceId" | "tradeId" | "messageId">,
) {
  if (params.traceId || params.tradeId || params.messageId) {
    return true
  }
  return !isLowSignalInfrastructureTrace(trace)
}

function isLowSignalInfrastructureTrace(trace: NormalizedTrace) {
  if (trace.tradeId || trace.messageId) {
    return false
  }

  const hasMeaningfulStage = trace.stages.some((stage) =>
    ["HTTP", "INGESTION", "MATCHING", "NETTING", "SETTLEMENT"].includes(stage.name),
  )
  if (hasMeaningfulStage) {
    return false
  }

  const root = trace.rootSpan.toLowerCase()
  const rootLooksLowSignal = LOW_SIGNAL_ROOT_PATTERNS.some((pattern) => root.includes(pattern))
  const allOperationsLookLowSignal = trace.rawSpans.every((span) => {
    const operation = span.operationName.toLowerCase()
    return LOW_SIGNAL_OPERATION_PATTERNS.some((pattern) => operation.includes(pattern))
  })

  return rootLooksLowSignal || allOperationsLookLowSignal
}

function tracePriority(trace: Pick<NormalizedTrace, "tradeId" | "messageId" | "stages" | "rootSpan">) {
  let score = 0
  if (trace.tradeId) score += 100
  if (trace.messageId) score += 50
  for (const stage of trace.stages) {
    switch (stage.name) {
      case "HTTP":
      case "INGESTION":
      case "MATCHING":
      case "NETTING":
      case "SETTLEMENT":
        score += stage.explicit ? 12 : 6
        break
      case "DATABASE":
        score += stage.explicit ? 2 : 1
        break
      default:
        break
    }
  }
  if (LOW_SIGNAL_ROOT_PATTERNS.some((pattern) => trace.rootSpan.toLowerCase().includes(pattern))) {
    score -= 25
  }
  return score
}

function compareTraces(
  a: Pick<NormalizedTrace, "tradeId" | "messageId" | "stages" | "rootSpan" | "startTime">,
  b: Pick<NormalizedTrace, "tradeId" | "messageId" | "stages" | "rootSpan" | "startTime">,
) {
  const scoreDiff = tracePriority(b) - tracePriority(a)
  if (scoreDiff !== 0) return scoreDiff
  return b.startTime - a.startTime
}

function inferStage(operationName: string, serviceName: string, tags: Record<string, string>) {
  const explicitStageValue = firstNonEmpty(EXPLICIT_STAGE_KEYS.map((key) => tags[key]))
  if (explicitStageValue) {
    return { stage: toCanonicalStage(explicitStageValue), inferred: false }
  }

  const queueName = firstNonEmpty(QUEUE_NAME_KEYS.map((key) => tags[key]))
  if (queueName) {
    return { stage: inferStageFromText(queueName), inferred: false }
  }

  if ("db.system" in tags || "db.statement" in tags) {
    return { stage: "DATABASE" as ClsStageName, inferred: false }
  }

  return {
    stage: inferStageFromText([operationName, serviceName, tags["http.route"], tags["http.target"]].join(" ")),
    inferred: true,
  }
}

function inferStageFromText(value: string): ClsStageName {
  const text = value.toLowerCase()
  if (text.includes("/api/trades") || text.includes("http") || text.includes("post /api/trades")) return "HTTP"
  if (text.includes("ingest") || text.includes("validation") || text.includes("ingestion")) return "INGESTION"
  if (text.includes("match")) return "MATCHING"
  if (text.includes("netting")) return "NETTING"
  if (text.includes("settlement") || text.includes("2pc") || text.includes("two phase")) return "SETTLEMENT"
  if (text.includes("database") || text.includes("jdbc") || text.includes("sql")) return "DATABASE"
  return "OTHER"
}

function toCanonicalStage(value: string): ClsStageName {
  const normalized = value.trim().toUpperCase()
  if (CLS_STAGE_ORDER.includes(normalized as ClsStageName)) {
    return normalized as ClsStageName
  }
  return inferStageFromText(value)
}

function isErrorSpan(tags: Record<string, string>) {
  const errorValue = (tags.error ?? "").toLowerCase()
  const statusCode = (tags["otel.status_code"] ?? tags["status.code"] ?? "").toLowerCase()
  return errorValue === "true" || statusCode === "error"
}

function flattenTags(tags: JaegerTag[]) {
  const result: Record<string, string> = {}
  for (const tag of tags) {
    result[tag.key] = stringifyTagValue(tag.value)
  }
  return result
}

function stringifyTagValue(value: JaegerValue) {
  if (value === null || value === undefined) return ""
  return String(value)
}

function extractTraceId(trace: JaegerTrace) {
  return trace.traceID ?? trace.traceId ?? ""
}

function findRootSpan(spans: TraceSpan[]) {
  return spans.find((span) => !span.parentSpanId)?.operationName ?? spans[0]?.operationName ?? "unknown"
}

function firstNonEmpty(values: Array<string | undefined>) {
  return values.find((value) => typeof value === "string" && value.trim().length > 0)
}

function unique(values: string[]) {
  return [...new Set(values)]
}

function toEpochMs(value: number) {
  return Math.floor(value / 1000)
}

function toDurationMs(value: number) {
  return Math.max(1, Math.floor(value / 1000))
}

export function renderTraceViewAscii(
  data: TraceViewData,
  options: {
    maxFlows?: number
  } = {},
) {
  const maxFlows = Math.max(1, options.maxFlows ?? 3)
  const flows = data.flows.slice(0, maxFlows)

  if (!flows.length) {
    return [
      "Exact Traced Component Interactions (span-derived only)",
      "  (no correlated flows found)",
      "",
      "Exact Traced HTTP Interactions (span-derived only)",
      "  (no HTTP spans found)",
    ].join("\n")
  }

  const componentLines = [
    "Exact Traced Component Interactions (span-derived only)",
    "Do not rename components or invent stages that are not present in spans.",
    "",
  ]
  const httpLines = [
    "Exact Traced HTTP Interactions (span-derived only)",
    "If a request or stage is absent from spans, treat it as absent.",
    "",
  ]

  for (const flow of flows) {
    const label = flow.tradeId ?? flow.messageId ?? flow.traceId
    const componentOps = unique(
      flow.rawSpans
        .filter((span) => isRelevantComponentSpan(span))
        .map((span) => span.operationName),
    )
    const httpOps = unique(
      flow.rawSpans
        .map((span) => formatHttpInteraction(span))
        .filter((value): value is string => Boolean(value)),
    )

    componentLines.push(`${label}`)
    if (componentOps.length === 0) {
      componentLines.push("  (no component spans)")
    } else {
      componentLines.push(`  ${componentOps.join("\n  -> ")}`)
    }

    httpLines.push(`${label}`)
    if (httpOps.length === 0) {
      httpLines.push("  (no HTTP spans)")
    } else {
      httpLines.push(`  ${httpOps.join("\n  -> ")}`)
    }

    componentLines.push("")
    httpLines.push("")
  }

  return [...trimTrailingBlankLines(componentLines), "", ...trimTrailingBlankLines(httpLines)].join("\n")
}

function isRelevantComponentSpan(span: TraceSpan) {
  const kind = span.tags["component.kind"] ?? ""
  if (kind === "controller" || kind === "service" || kind === "repository") {
    return true
  }

  const operation = span.operationName.toLowerCase()
  if (LOW_SIGNAL_OPERATION_PATTERNS.some((pattern) => operation.includes(pattern))) {
    return false
  }

  return span.stage !== "OTHER" || Boolean(span.tags["cls.stage"])
}

function formatHttpInteraction(span: TraceSpan) {
  const method = firstNonEmpty([span.tags["http.request.method"], span.tags["http.method"]])
  const route = firstNonEmpty([span.tags["url.path"], span.tags["http.route"], span.tags["http.target"]])
  if (!method && !route && span.stage !== "HTTP") {
    return undefined
  }

  const endpoint = [method, route].filter(Boolean).join(" ").trim() || span.operationName
  const component = span.operationName !== endpoint ? ` -> ${span.operationName}` : ""
  return `${endpoint}${component}`
}

function trimTrailingBlankLines(lines: string[]) {
  const copy = [...lines]
  while (copy.at(-1) === "") {
    copy.pop()
  }
  return copy
}

export function createTraceViewAssets(input: { title: string; refreshSeconds: number }) {
  const title = escapeHtml(input.title)

  const indexHtml = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${title}</title>
    <link rel="stylesheet" href="./styles.css" />
  </head>
  <body>
    <div class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Bootstrap Trace Viewer</p>
          <h1>CLS Trace Viewer</h1>
          <p id="meta" class="meta">Loading traces...</p>
        </div>
        <div class="pill-row">
          <span class="pill" id="refresh-pill">Refresh every ${input.refreshSeconds}s</span>
          <span class="pill" id="status-pill">Connecting</span>
        </div>
      </header>

      <section class="filters">
        <label>
          <span>Trade ID</span>
          <input id="trade-filter" type="text" placeholder="TRD-100" />
        </label>
        <label>
          <span>Message ID</span>
          <input id="message-filter" type="text" placeholder="MSG-100" />
        </label>
        <label>
          <span>Trace ID</span>
          <input id="trace-filter" type="text" placeholder="trace id" />
        </label>
        <label>
          <span>Status</span>
          <select id="status-filter">
            <option value="">All</option>
            <option value="ok">ok</option>
            <option value="partial">partial</option>
            <option value="error">error</option>
          </select>
        </label>
      </section>

      <main class="content">
        <section class="panel list-panel">
          <div class="panel-header">
            <h2>Flows</h2>
            <span id="flow-count" class="panel-count">0</span>
          </div>
          <div id="flow-list" class="flow-list"></div>
        </section>

        <section class="panel detail-panel">
          <div id="detail"></div>
        </section>
      </main>
    </div>
    <script src="./app.js"></script>
  </body>
</html>
`

  const stylesCss = `:root {
  --bg: #f4f1ea;
  --panel: #fffdf8;
  --ink: #1f2a1f;
  --muted: #5f6b5f;
  --line: #d8d0c3;
  --accent: #1d6b53;
  --accent-soft: #d8efe6;
  --warn: #c26a1b;
  --warn-soft: #fff1df;
  --danger: #b63f2d;
  --danger-soft: #fde8e3;
  --shadow: 0 18px 40px rgba(31, 42, 31, 0.08);
  font-family: "IBM Plex Sans", "Avenir Next", sans-serif;
}

* { box-sizing: border-box; }
body {
  margin: 0;
  background:
    radial-gradient(circle at top left, rgba(29, 107, 83, 0.16), transparent 30%),
    linear-gradient(135deg, #f7f3eb, #efe7d8);
  color: var(--ink);
}

.shell {
  min-height: 100vh;
  padding: 24px;
}

.topbar, .panel, .filters {
  background: var(--panel);
  border: 1px solid rgba(216, 208, 195, 0.9);
  border-radius: 22px;
  box-shadow: var(--shadow);
}

.topbar {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 28px;
  margin-bottom: 18px;
}

.eyebrow {
  margin: 0 0 8px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
}

h1, h2, h3 {
  margin: 0;
  font-family: "Iowan Old Style", "Palatino Linotype", serif;
}

.meta, .subtle {
  color: var(--muted);
}

.pill-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-content: flex-start;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  padding: 8px 14px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 13px;
  font-weight: 700;
}

.filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  padding: 18px;
  margin-bottom: 18px;
}

.filters label {
  display: grid;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--muted);
}

.filters input, .filters select {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px 12px;
  font: inherit;
  background: #fff;
}

.content {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
}

.panel {
  padding: 18px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.panel-count {
  color: var(--muted);
  font-size: 13px;
}

.flow-list {
  display: grid;
  gap: 10px;
  max-height: calc(100vh - 260px);
  overflow: auto;
}

.flow-card {
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 14px;
  background: #fff;
  cursor: pointer;
}

.flow-card.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(29, 107, 83, 0.12);
}

.flow-card h3 {
  font-size: 18px;
  margin-bottom: 6px;
}

.badge-row, .detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 700;
  background: #efebe2;
  color: var(--ink);
}

.badge.ok { background: var(--accent-soft); color: var(--accent); }
.badge.partial { background: var(--warn-soft); color: var(--warn); }
.badge.error { background: var(--danger-soft); color: var(--danger); }
.badge.missing { background: #f1f1f1; color: #666; }

.stage-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin: 18px 0 24px;
}

.stage-card {
  min-width: 150px;
  border-radius: 18px;
  border: 1px solid var(--line);
  padding: 14px;
  background: #fff;
}

.stage-card.ok { border-color: rgba(29, 107, 83, 0.4); background: linear-gradient(180deg, #f9fffc, #ffffff); }
.stage-card.partial { border-color: rgba(194, 106, 27, 0.4); background: linear-gradient(180deg, #fff9f0, #ffffff); }
.stage-card.error { border-color: rgba(182, 63, 45, 0.4); background: linear-gradient(180deg, #fff5f3, #ffffff); }
.stage-card.missing { background: #f6f4ef; color: #7a7a7a; }

.stage-arrow {
  color: var(--muted);
  font-weight: 700;
}

.span-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.span-table th, .span-table td {
  text-align: left;
  padding: 10px 8px;
  border-bottom: 1px solid #ece6db;
  vertical-align: top;
}

.empty {
  color: var(--muted);
  padding: 18px 0;
}

@media (max-width: 960px) {
  .filters, .content {
    grid-template-columns: 1fr;
  }

  .topbar {
    flex-direction: column;
  }

  .flow-list {
    max-height: none;
  }
}
`

  const appJs = `const STAGE_ORDER = ${JSON.stringify(CLS_STAGE_ORDER)};
const REFRESH_SECONDS = ${Math.max(1, input.refreshSeconds)};

const state = {
  dataset: null,
  selectedKey: null,
  filters: { tradeId: "", messageId: "", traceId: "", status: "" },
};

const els = {
  meta: document.getElementById("meta"),
  statusPill: document.getElementById("status-pill"),
  flowCount: document.getElementById("flow-count"),
  flowList: document.getElementById("flow-list"),
  detail: document.getElementById("detail"),
  tradeFilter: document.getElementById("trade-filter"),
  messageFilter: document.getElementById("message-filter"),
  traceFilter: document.getElementById("trace-filter"),
  statusFilter: document.getElementById("status-filter"),
};

function setStatus(label, tone) {
  els.statusPill.textContent = label;
  els.statusPill.className = "pill " + (tone || "");
}

function formatTimestamp(value) {
  return new Date(value).toLocaleString();
}

function formatDuration(value) {
  return value + " ms";
}

function readFilters() {
  state.filters.tradeId = els.tradeFilter.value.trim().toLowerCase();
  state.filters.messageId = els.messageFilter.value.trim().toLowerCase();
  state.filters.traceId = els.traceFilter.value.trim().toLowerCase();
  state.filters.status = els.statusFilter.value;
}

function flowMatches(flow) {
  if (state.filters.tradeId && !(flow.tradeId || "").toLowerCase().includes(state.filters.tradeId)) return false;
  if (state.filters.messageId && !(flow.messageId || "").toLowerCase().includes(state.filters.messageId)) return false;
  if (state.filters.traceId) {
    const haystack = [flow.traceId].concat(flow.traceIds || []).join(" ").toLowerCase();
    if (!haystack.includes(state.filters.traceId)) return false;
  }
  if (state.filters.status && flow.status !== state.filters.status) return false;
  return true;
}

function visibleFlows() {
  if (!state.dataset) return [];
  return state.dataset.flows.filter(flowMatches);
}

function ensureSelection(flows) {
  if (!flows.length) {
    state.selectedKey = null;
    return;
  }
  if (!state.selectedKey || !flows.some((flow) => flow.groupKey === state.selectedKey)) {
    state.selectedKey = flows[0].groupKey;
  }
}

function renderList(flows) {
  els.flowCount.textContent = String(flows.length);
  if (!flows.length) {
    els.flowList.innerHTML = '<p class="empty">No flows matched the current filters.</p>';
    return;
  }

  els.flowList.innerHTML = flows.map((flow) => {
    const title = flow.tradeId || flow.messageId || flow.traceId;
    return '<article class="flow-card ' + (flow.groupKey === state.selectedKey ? "selected" : "") + '" data-key="' + escapeHtml(flow.groupKey) + '">' +
      '<h3>' + escapeHtml(title) + '</h3>' +
      '<p class="subtle">' + escapeHtml(flow.rootSpan) + '</p>' +
      '<div class="badge-row">' +
        '<span class="badge ' + flow.status + '">' + escapeHtml(flow.status) + '</span>' +
        '<span class="badge">' + escapeHtml(flow.grouping) + '</span>' +
        '<span class="badge">' + escapeHtml(String(flow.attempts)) + ' attempt(s)</span>' +
      '</div>' +
      '<p class="subtle">Started ' + escapeHtml(formatTimestamp(flow.startTime)) + '</p>' +
    '</article>';
  }).join("");

  for (const card of els.flowList.querySelectorAll(".flow-card")) {
    card.addEventListener("click", () => {
      state.selectedKey = card.getAttribute("data-key");
      render();
    });
  }
}

function renderDetail(flow) {
  if (!flow) {
    els.detail.innerHTML = '<p class="empty">Select a flow to inspect the CLS stage map.</p>';
    return;
  }

  const stageMap = new Map(flow.stages.map((stage) => [stage.name, stage]));
  const stageRail = STAGE_ORDER
    .map((name, index) => {
      const stage = stageMap.get(name);
      const card = stage
        ? '<div class="stage-card ' + stage.status + '">' +
            '<div class="badge-row"><span class="badge ' + stage.status + '">' + escapeHtml(stage.status) + '</span>' +
            (stage.inferred ? '<span class="badge partial">inferred</span>' : "") +
            '</div>' +
            '<h3>' + escapeHtml(stage.name) + '</h3>' +
            '<p class="subtle">' + escapeHtml(stage.operationNames.join(", ")) + '</p>' +
            '<p class="subtle">' + escapeHtml(formatDuration(stage.durationMs)) + ' • ' + escapeHtml(String(stage.spanCount)) + ' span(s)</p>' +
          '</div>'
        : '<div class="stage-card missing"><h3>' + escapeHtml(name) + '</h3><p class="subtle">missing</p></div>';
      const arrow = index < STAGE_ORDER.length - 1 ? '<div class="stage-arrow">→</div>' : "";
      return card + arrow;
    })
    .join("");

  const spanRows = flow.rawSpans
    .map((span) => '<tr>' +
      '<td>' + escapeHtml(span.operationName) + '</td>' +
      '<td>' + escapeHtml(span.stage) + '</td>' +
      '<td>' + escapeHtml(span.serviceName) + '</td>' +
      '<td>' + escapeHtml(formatDuration(span.durationMs)) + '</td>' +
      '<td>' + escapeHtml(formatTimestamp(span.startTime)) + '</td>' +
      '</tr>')
    .join("");

  els.detail.innerHTML =
    '<div class="panel-header"><div><h2>' + escapeHtml(flow.tradeId || flow.messageId || flow.traceId) + '</h2>' +
      '<p class="subtle">' + escapeHtml(flow.traceId) + '</p></div>' +
      '<div class="detail-meta">' +
        '<span class="badge ' + flow.status + '">' + escapeHtml(flow.status) + '</span>' +
        (flow.inferred ? '<span class="badge partial">best effort</span>' : '') +
        '<span class="badge">' + escapeHtml(String(flow.attempts)) + ' attempt(s)</span>' +
      '</div></div>' +
    '<p class="subtle">Jaeger trace generated ' + escapeHtml(state.dataset.generatedAt) + '</p>' +
    '<div class="stage-rail">' + stageRail + '</div>' +
    '<h3>Raw spans</h3>' +
    '<table class="span-table"><thead><tr><th>Operation</th><th>Stage</th><th>Service</th><th>Duration</th><th>Start</th></tr></thead><tbody>' + spanRows + '</tbody></table>';
}

function render() {
  const flows = visibleFlows();
  ensureSelection(flows);
  renderList(flows);
  const flow = flows.find((item) => item.groupKey === state.selectedKey);
  renderDetail(flow);
  if (state.dataset) {
    els.meta.textContent = state.dataset.service + " via " + state.dataset.jaegerBaseUrl + " • Last refresh " + new Date(state.dataset.generatedAt).toLocaleTimeString();
  }
}

async function load(url, tone) {
  const response = await fetch(url);
  if (!response.ok) throw new Error('Failed to load ' + url + ': ' + response.status);
  const data = await response.json();
  state.dataset = data;
  setStatus("Connected", tone || "ok");
  render();
}

async function refresh() {
  try {
    await load("./api/traces", "ok");
  } catch (error) {
    setStatus("Refresh failed", "error");
    console.error(error);
  }
}

for (const input of [els.tradeFilter, els.messageFilter, els.traceFilter, els.statusFilter]) {
  input.addEventListener("input", () => {
    readFilters();
    render();
  });
}

load("./trace-data.json", "partial").then(() => refresh()).catch(async () => {
  await refresh();
});

setInterval(refresh, REFRESH_SECONDS * 1000);

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
`

  return { indexHtml, stylesCss, appJs }
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
}

export function resolveTraceViewOutputDir(root: string, serviceName: string, explicitOutputDir?: string) {
  return path.resolve(explicitOutputDir ?? path.join(root, ".bootstrap", "traceview", serviceName))
}
