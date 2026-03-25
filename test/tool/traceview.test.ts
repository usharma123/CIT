import { afterEach, describe, expect, test } from "bun:test"
import fs from "fs/promises"
import path from "path"
import { Instance } from "../../src/project/instance"
import { TraceViewServer } from "../../src/server/traceview"
import { normalizeTrace } from "../../src/tool/traceview-lib"
import { TraceViewTool } from "../../src/tool/traceview"
import { tmpdir } from "../fixture/fixture"

const ctx = {
  sessionID: "test",
  messageID: "",
  callID: "",
  agent: "build",
  abort: AbortSignal.any([]),
  metadata: () => {},
  ask: async () => {},
}

afterEach(async () => {
  await TraceViewServer.stopAll()
})

describe("tool.traceview", () => {
  test("builds viewer assets and serves normalized traces from Jaeger", async () => {
    using jaeger = createJaegerFixtureServer()
    await using tmp = await tmpdir({ git: true })

    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const tool = await TraceViewTool.init()
        const result = await tool.execute(
          {
            jaegerBaseUrl: jaeger.url.origin,
            serviceName: "mocknet",
            lookbackMinutes: 15,
            limit: 10,
            refreshSeconds: 2,
            open: false,
          },
          ctx,
        )

        expect(result.output).toContain("Prepared CLS trace viewer for mocknet.")
        const outputDir = path.join(tmp.path, ".bootstrap", "traceview", "mocknet")

        const indexHtml = await fs.readFile(path.join(outputDir, "index.html"), "utf8")
        const appJs = await fs.readFile(path.join(outputDir, "app.js"), "utf8")
        const traceData = JSON.parse(await fs.readFile(path.join(outputDir, "trace-data.json"), "utf8"))

        expect(indexHtml).toContain("CLS Trace Viewer")
        expect(indexHtml).toContain("Refresh every 2s")
        expect(appJs).toContain("const REFRESH_SECONDS = 2;")
        expect(traceData.flows).toHaveLength(2)
        const tradeFlow = traceData.flows.find((flow: { tradeId?: string }) => flow.tradeId === "TRD-100")
        expect(tradeFlow).toBeDefined()
        expect(result.output).toContain("Exact Traced Component Interactions")
        expect(result.output).toContain("TradeSubmissionController.submitTrade")
        expect(result.output).toContain("Do not rename components or invent stages")
        expect(result.output).toContain("Exact Traced HTTP Interactions")
        expect(result.output).toContain("POST /api/trades")
        expect(tradeFlow.stages.map((stage: { name: string }) => stage.name)).toEqual([
          "HTTP",
          "INGESTION",
          "MATCHING",
          "NETTING",
          "SETTLEMENT",
          "DATABASE",
        ])
        expect(tradeFlow.rawSpans.some((span: { operationName: string }) => span.operationName === "TradeIngestionService.processTradeXml")).toBe(true)
        expect(tradeFlow.rawSpans.some((span: { operationName: string }) => span.operationName === "TradeRepository.save")).toBe(true)

        const viewerUrl = result.metadata.viewerUrl as string
        const page = await fetch(viewerUrl).then((response) => response.text())
        expect(page).toContain("CLS Trace Viewer")

        const apiData = await fetch(new URL("/api/traces", viewerUrl)).then((response) => response.json())
        expect(apiData.service).toBe("mocknet")
        expect(apiData.flows.some((flow: { grouping: string; traceId: string }) => flow.grouping === "traceId" && flow.traceId === "trace-raw")).toBe(true)

        const traceDetail = await fetch(new URL("/api/traces/trace-raw", viewerUrl)).then((response) => response.json())
        expect(traceDetail.traceId).toBe("trace-raw")
        expect(traceDetail.stages.some((stage: { name: string }) => stage.name === "MATCHING")).toBe(true)
      },
    })
  })

  test("falls back to traceId grouping and inferred stage mapping when CLS tags are absent", () => {
    const normalized = normalizeTrace(rawTraceFixture())!
    expect(normalized.tradeId).toBeUndefined()
    expect(normalized.messageId).toBeUndefined()
    expect(normalized.status).toBe("partial")
    expect(normalized.stages.map((stage) => stage.name)).toEqual(["HTTP", "MATCHING", "DATABASE"])
  })

  test("retains trade ids on grouped attempts so the newest trace can represent the flow", () => {
    const older = normalizeTrace(buildGroupedTrace("trace-older", 1_700_000_000_000_000))!
    const newer = normalizeTrace(buildGroupedTrace("trace-newer", 1_700_000_100_000_000))!

    expect(older.tradeId).toBe("TRD-GROUP")
    expect(newer.tradeId).toBe("TRD-GROUP")
    expect(newer.startTime).toBeGreaterThan(older.startTime)
  })

  test("suppresses low-signal queue polling traces from the default flow list", async () => {
    using jaeger = createJaegerFixtureServer()
    await using tmp = await tmpdir({ git: true })

    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const tool = await TraceViewTool.init()
        const result = await tool.execute(
          {
            jaegerBaseUrl: jaeger.url.origin,
            serviceName: "mocknet",
            lookbackMinutes: 15,
            limit: 10,
            refreshSeconds: 2,
            open: false,
          },
          ctx,
        )

        const viewerUrl = result.metadata.viewerUrl as string
        const apiData = await fetch(new URL("/api/traces", viewerUrl)).then((response) => response.json())

        expect(apiData.flows.some((flow: { traceId: string }) => flow.traceId === "trace-poll")).toBe(false)
        expect(apiData.traces.some((trace: { traceId: string }) => trace.traceId === "trace-poll")).toBe(false)
      },
    })
  })
})

function createJaegerFixtureServer() {
  const searchPayload = {
    data: [{ traceID: "trace-cls" }, { traceID: "trace-raw" }, { traceID: "trace-poll" }],
  }

  return Bun.serve({
    port: 0,
    fetch(req) {
      const url = new URL(req.url)
      if (url.pathname === "/api/traces") {
        return Response.json(searchPayload)
      }
      if (url.pathname === "/api/traces/trace-cls") {
        return Response.json({ data: [clsTraceFixture()] })
      }
      if (url.pathname === "/api/traces/trace-raw") {
        return Response.json({ data: [rawTraceFixture()] })
      }
      if (url.pathname === "/api/traces/trace-poll") {
        return Response.json({ data: [pollingTraceFixture()] })
      }
      return new Response("Not found", { status: 404 })
    },
  })
}

function clsTraceFixture() {
  const base = 1_700_000_000_000_000
  return {
    traceID: "trace-cls",
    processes: {
      p1: { serviceName: "mocknet" },
      p2: { serviceName: "mocknet-db" },
    },
    spans: [
      span("trace-cls", "1", undefined, "TradeSubmissionController.submitTrade", "p1", base, 800_000, [
        tag("cls.stage", "HTTP"),
        tag("component.kind", "controller"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
      ]),
      span("trace-cls", "2", "1", "TradeIngestionService.processTradeXml", "p1", base + 50_000, 90_000, [
        tag("cls.stage", "INGESTION"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
        tag("queue.name", "INGESTION"),
      ]),
      span("trace-cls", "3", "2", "TradeMatchingEngine.processMatchingMessage", "p1", base + 170_000, 100_000, [
        tag("cls.stage", "MATCHING"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
      ]),
      span("trace-cls", "4", "3", "NettingCalculator.processNettingMessage", "p1", base + 320_000, 110_000, [
        tag("cls.stage", "NETTING"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
      ]),
      span("trace-cls", "5", "4", "TwoPhaseCommitCoordinator.executeTransaction", "p1", base + 470_000, 120_000, [
        tag("cls.stage", "SETTLEMENT"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
      ]),
      span("trace-cls", "6", "2", "TradeRepository.save", "p2", base + 210_000, 60_000, [
        tag("cls.stage", "DATABASE"),
        tag("component.kind", "repository"),
        tag("trade.id", "TRD-100"),
      ]),
    ],
  }
}

function rawTraceFixture() {
  const base = 1_700_000_010_000_000
  return {
    traceID: "trace-raw",
    processes: {
      p1: { serviceName: "mocknet" },
      p2: { serviceName: "mocknet-db" },
    },
    spans: [
      span("trace-raw", "11", undefined, "POST /api/trades", "p1", base, 500_000, [
        tag("http.method", "POST"),
        tag("http.route", "/api/trades"),
      ]),
      span("trace-raw", "12", "11", "matching engine", "p1", base + 90_000, 150_000, []),
      span("trace-raw", "13", "12", "select matched trades", "p2", base + 160_000, 70_000, [
        tag("db.system", "h2"),
      ]),
    ],
  }
}

function buildGroupedTrace(traceID: string, base: number) {
  return {
    traceID,
    processes: {
      p1: { serviceName: "mocknet" },
    },
    spans: [
      span(traceID, "1", undefined, "POST /api/trades", "p1", base, 100_000, [
        tag("trade.id", "TRD-GROUP"),
        tag("message.id", `MSG-${traceID}`),
        tag("http.route", "/api/trades"),
      ]),
      span(traceID, "2", "1", "ingestion worker", "p1", base + 20_000, 40_000, [
        tag("component.stage", "INGESTION"),
        tag("trade.id", "TRD-GROUP"),
      ]),
    ],
  }
}

function pollingTraceFixture() {
  const base = 1_700_000_020_000_000
  return {
    traceID: "trace-poll",
    processes: {
      p1: { serviceName: "mocknet" },
      p2: { serviceName: "mocknet-db" },
    },
    spans: [
      span("trace-poll", "21", undefined, "QueueBroker.claimNext", "p1", base, 40_000, []),
      span("trace-poll", "22", "21", "QueueMessageRepository.findClaimableNewIds", "p1", base + 5_000, 20_000, []),
      span("trace-poll", "23", "21", "SELECT ./data/coredb.queue_messages", "p2", base + 10_000, 10_000, [
        tag("db.system", "h2"),
      ]),
    ],
  }
}

function span(
  traceID: string,
  spanID: string,
  parentSpanID: string | undefined,
  operationName: string,
  processID: string,
  startTime: number,
  duration: number,
  tags: Array<{ key: string; value: string | boolean | number }>,
) {
  return {
    traceID,
    spanID,
    operationName,
    processID,
    startTime,
    duration,
    tags,
    references: parentSpanID
      ? [
          {
            refType: "CHILD_OF",
            spanID: parentSpanID,
          },
        ]
      : [],
  }
}

function tag(key: string, value: string | boolean | number) {
  return { key, value }
}
