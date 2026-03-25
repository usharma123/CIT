import fs from "fs/promises"
import open from "open"
import z from "zod"
import { Tool } from "./tool"
import DESCRIPTION from "./traceview.txt"
import { Instance } from "@/project/instance"
import { TraceViewServer } from "@/server/traceview"
import {
  createTraceViewAssets,
  fetchTraceViewData,
  renderTraceViewAscii,
  resolveTraceViewOutputDir,
} from "./traceview-lib"

export const TraceViewTool = Tool.define("traceview", async () => {
  return {
    description: DESCRIPTION.replaceAll("${directory}", Instance.directory),
    parameters: z.object({
      jaegerBaseUrl: z.string().default("http://localhost:16686").describe("Jaeger base URL"),
      serviceName: z.string().default("mocknet").describe("Jaeger service name"),
      lookbackMinutes: z.number().int().positive().default(15).describe("How far back to query Jaeger"),
      limit: z.number().int().positive().default(50).describe("Maximum traces to fetch"),
      traceId: z.string().optional().describe("Optional trace ID to inspect directly"),
      tradeId: z.string().optional().describe("Optional trade ID filter"),
      messageId: z.string().optional().describe("Optional message ID filter"),
      refreshSeconds: z.number().int().positive().default(3).describe("Browser poll interval in seconds"),
      open: z.boolean().default(true).describe("Open the local viewer in the browser"),
      ascii: z.boolean().default(true).describe("Include ASCII interaction summaries in tool output"),
      asciiFlowLimit: z.number().int().positive().default(3).describe("Maximum flows to summarize in ASCII"),
      outputDir: z.string().optional().describe("Directory for generated viewer assets"),
    }),
    async execute(params) {
      const outputDir = resolveTraceViewOutputDir(Instance.directory, params.serviceName, params.outputDir)
      await fs.mkdir(outputDir, { recursive: true })

      const dataLoader = () =>
        fetchTraceViewData({
          jaegerBaseUrl: params.jaegerBaseUrl,
          serviceName: params.serviceName,
          lookbackMinutes: params.lookbackMinutes,
          limit: params.limit,
          traceId: params.traceId,
          tradeId: params.tradeId,
          messageId: params.messageId,
        })

      const initialData = await dataLoader()
      const assets = createTraceViewAssets({
        title: `${params.serviceName} CLS Trace Viewer`,
        refreshSeconds: params.refreshSeconds,
      })

      await fs.writeFile(`${outputDir}/index.html`, assets.indexHtml)
      await fs.writeFile(`${outputDir}/styles.css`, assets.stylesCss)
      await fs.writeFile(`${outputDir}/app.js`, assets.appJs)
      await fs.writeFile(`${outputDir}/trace-data.json`, JSON.stringify(initialData, null, 2))

      const server = await TraceViewServer.start({
        outputDir,
        loadData: async () => {
          const freshData = await dataLoader()
          await fs.writeFile(`${outputDir}/trace-data.json`, JSON.stringify(freshData, null, 2))
          return freshData
        },
      })

      const viewerUrl = new URL("/", server.url).toString()
      if (params.open) {
        await open(viewerUrl)
      }

      const asciiSummary = (params.ascii ?? true)
        ? renderTraceViewAscii(initialData, {
            maxFlows: params.asciiFlowLimit ?? 3,
          })
        : null

      return {
        title: `Prepared trace viewer for ${params.serviceName}`,
        metadata: {
          viewerUrl,
          outputDir,
          serviceName: params.serviceName,
          jaegerBaseUrl: params.jaegerBaseUrl,
          traceCount: initialData.traces.length,
          flowCount: initialData.flows.length,
        },
        output: [
          `Prepared CLS trace viewer for ${params.serviceName}.`,
          `Viewer: ${viewerUrl}`,
          `Assets: ${outputDir}`,
          `Flows: ${initialData.flows.length}`,
          `Traces: ${initialData.traces.length}`,
          ...(asciiSummary ? ["", asciiSummary] : []),
        ].join("\n"),
      }
    },
  }
})
