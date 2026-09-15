import { describe, expect, test } from "bun:test"
import path from "path"
import { Instance } from "../../src/project/instance"
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

describe("tool.traceview", () => {
  test("builds terminal flow diagrams from Jaeger queries", async () => {
    using jaeger = createJaegerFixtureServer()
    await using tmp = await tmpdir({ git: true })

    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const tool = await TraceViewTool.init()
        expect(tool.description).toContain("Treat queue polling traces and infrastructure-only traces as insufficient evidence")
        expect(tool.description).toContain("If async queue stages appear as separate traces, say so explicitly")
        expect(tool.description).toContain("Use the Jaeger `processes` map for service attribution")
        const result = await tool.execute(
          {
            jaegerBaseUrl: jaeger.url.origin,
            serviceName: "mocknet",
            lookbackMinutes: 15,
            limit: 10,
            ensureRunning: false,
            refreshSeconds: 2,
            open: false,
            ascii: true,
            asciiFlowLimit: 3,
          },
          ctx,
        )

        expect(result.output).toContain("Prepared terminal trace flow for mocknet.")
        expect(result.output).toContain("Jaeger Query Summary")
        expect(result.output).toContain("Component Flow (terminal ASCII, span-derived only)")
        expect(result.output).toContain("HTTP Flow (terminal ASCII, span-derived only)")
        expect(result.output).toContain("[HTTP] TradeSubmissionController.submitTrade")
        expect(result.output).toContain("[HTTP] POST /api/trades")
        expect(result.output).toContain("[INGESTION] TradeIngestionService.processTradeXml")
        expect(result.output).toContain("[SETTLEMENT] TwoPhaseCommitCoordinator.executeTransaction")
        const outputDir = path.join(tmp.path, ".bootstrap", "traceview", "mocknet")
        expect(result.metadata.viewerUrl).toBeUndefined()
        expect(result.metadata.querySummary).toBeDefined()
        expect(result.metadata.querySummary.componentOperations).toContain("TradeSubmissionController.submitTrade")
        expect(result.metadata.querySummary.httpOperations).toContain("POST /api/trades")
        expect(result.metadata.flowCount).toBe(2)
        expect(await Bun.file(outputDir).exists()).toBe(false)
      },
    })
  })

  test("falls back to traceId grouping and inferred stage mapping when Mocknet tags are absent", () => {
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
            ensureRunning: false,
            refreshSeconds: 2,
            open: false,
            ascii: true,
            asciiFlowLimit: 3,
          },
          ctx,
        )
        expect(result.metadata.flowCount).toBe(2)
        expect(result.output).not.toContain("trace-poll")
      },
    })
  })
})

function createJaegerFixtureServer() {
  const searchPayload = {
    data: [{ traceID: "trace-pipeline" }, { traceID: "trace-raw" }, { traceID: "trace-poll" }],
  }

  return Bun.serve({
    port: 0,
    fetch(req) {
      const url = new URL(req.url)
      if (url.pathname === "/api/operations") {
        return Response.json({
          data: [
            "POST /api/trades",
            "TradeSubmissionController.submitTrade",
            "TradeIngestionService.processTradeXml",
            "TradeMatchingEngine.processMatchingMessage",
            "NettingCalculator.processNettingMessage",
            "TwoPhaseCommitCoordinator.executeTransaction",
            "QueueBroker.claimNext",
          ],
        })
      }
      if (url.pathname === "/api/traces") {
        return Response.json(searchPayload)
      }
      if (url.pathname === "/api/traces/trace-pipeline") {
        return Response.json({ data: [pipelineTraceFixture()] })
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

function pipelineTraceFixture() {
  const base = 1_700_000_000_000_000
  return {
    traceID: "trace-pipeline",
    processes: {
      p1: { serviceName: "mocknet" },
      p2: { serviceName: "mocknet-db" },
    },
    spans: [
      span("trace-pipeline", "1", undefined, "TradeSubmissionController.submitTrade", "p1", base, 800_000, [
        tag("component.stage", "HTTP"),
        tag("component.kind", "controller"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
      ]),
      span("trace-pipeline", "2", "1", "TradeIngestionService.processTradeXml", "p1", base + 50_000, 90_000, [
        tag("component.stage", "INGESTION"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
        tag("queue.name", "INGESTION"),
      ]),
      span("trace-pipeline", "3", "2", "TradeMatchingEngine.processMatchingMessage", "p1", base + 170_000, 100_000, [
        tag("component.stage", "MATCHING"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
        tag("message.id", "MSG-100"),
      ]),
      span("trace-pipeline", "4", "3", "NettingCalculator.processNettingMessage", "p1", base + 320_000, 110_000, [
        tag("component.stage", "NETTING"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
      ]),
      span("trace-pipeline", "5", "4", "TwoPhaseCommitCoordinator.executeTransaction", "p1", base + 470_000, 120_000, [
        tag("component.stage", "SETTLEMENT"),
        tag("component.kind", "service"),
        tag("trade.id", "TRD-100"),
      ]),
      span("trace-pipeline", "6", "2", "TradeRepository.save", "p2", base + 210_000, 60_000, [
        tag("component.stage", "DATABASE"),
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
