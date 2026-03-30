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
      ensureRunning: z.boolean().default(true).describe("Run the tracing launcher before querying Jaeger"),
      startupCommand: z.string().optional().describe("Command used to start Jaeger and the traced app"),
      traceId: z.string().optional().describe("Optional trace ID to inspect directly"),
      tradeId: z.string().optional().describe("Optional trade ID filter"),
      messageId: z.string().optional().describe("Optional message ID filter"),
      refreshSeconds: z.number().int().positive().default(3).describe("Browser poll interval in seconds"),
      open: z.boolean().default(false).describe("Open the optional local viewer in the browser"),
      ascii: z.boolean().default(true).describe("Include ASCII interaction summaries in tool output"),
      asciiFlowLimit: z.number().int().positive().default(3).describe("Maximum flows to summarize in ASCII"),
      outputDir: z.string().optional().describe("Directory for generated viewer assets"),
    }),
    async execute(params) {
      const startupCommand = params.startupCommand ?? defaultStartupCommand(params.serviceName)
      let startupOutput = ""

      if (params.ensureRunning && startupCommand) {
        const launch = Bun.spawn({
          cmd: ["bash", "-lc", startupCommand],
          cwd: Instance.directory,
          stdout: "pipe",
          stderr: "pipe",
        })
        const [stdout, stderr, exitCode] = await Promise.all([
          new Response(launch.stdout).text(),
          new Response(launch.stderr).text(),
          launch.exited,
        ])
        startupOutput = [stdout.trim(), stderr.trim()].filter(Boolean).join("\n")
        if (exitCode !== 0) {
          throw new Error(`Failed to start tracing workflow with \`${startupCommand}\`:\n${startupOutput}`)
        }
      }

      const outputDir = resolveTraceViewOutputDir(Instance.directory, params.serviceName, params.outputDir)

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
      let viewerUrl: string | undefined
      if (params.open) {
        await fs.mkdir(outputDir, { recursive: true })
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

        viewerUrl = new URL("/", server.url).toString()
        await open(viewerUrl)
      }

      const asciiSummary = (params.ascii ?? true)
        ? renderTraceViewAscii(initialData, {
            maxFlows: params.asciiFlowLimit ?? 3,
          })
        : null

      return {
        title: `Prepared terminal trace flow for ${params.serviceName}`,
        metadata: {
          startupCommand,
          startupOutput,
          viewerUrl,
          outputDir: params.open ? outputDir : undefined,
          serviceName: params.serviceName,
          jaegerBaseUrl: params.jaegerBaseUrl,
          traceCount: initialData.traces.length,
          flowCount: initialData.flows.length,
          querySummary: initialData.querySummary,
        },
        output: [
          `Prepared terminal trace flow for ${params.serviceName}.`,
          ...(startupCommand ? [`Startup: ${startupCommand}`] : []),
          ...(startupOutput ? [`Startup output: ${startupOutput}`] : []),
          `Flows: ${initialData.flows.length}`,
          `Traces: ${initialData.traces.length}`,
          ...(viewerUrl ? [`Viewer: ${viewerUrl}`, `Assets: ${outputDir}`] : []),
          ...(asciiSummary ? ["", asciiSummary] : []),
        ].join("\n"),
      }
    },
  }
})

function defaultStartupCommand(serviceName: string) {
  if (serviceName === "mocknet") {
    return "bun run mocknet:otel start"
  }
  return ""
}
