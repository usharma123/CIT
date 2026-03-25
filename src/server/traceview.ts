import path from "path"
import { existsSync } from "fs"
import type { TraceViewData } from "@/tool/traceview-lib"

interface TraceViewServerInput {
  outputDir: string
  loadData: () => Promise<TraceViewData>
}

interface TraceViewServerState extends TraceViewServerInput {
  server: Bun.Server<undefined>
}

const servers = new Map<string, TraceViewServerState>()

export const TraceViewServer = {
  async start(input: TraceViewServerInput) {
    const key = path.resolve(input.outputDir)
    const existing = servers.get(key)
    if (existing) {
      existing.outputDir = key
      existing.loadData = input.loadData
      return existing.server
    }

    const state = {} as TraceViewServerState
    const server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      async fetch(req) {
        const url = new URL(req.url)

        if (url.pathname === "/api/traces") {
          const data = await state.loadData()
          return Response.json(data)
        }

        if (url.pathname.startsWith("/api/traces/")) {
          const traceId = decodeURIComponent(url.pathname.slice("/api/traces/".length))
          const data = await state.loadData()
          const trace = data.traces.find((item) => item.traceId === traceId)
          if (!trace) {
            return new Response("Not found", { status: 404 })
          }
          return Response.json(trace)
        }

        return serveStaticFile(state.outputDir, url.pathname)
      },
    })

    state.outputDir = key
    state.loadData = input.loadData
    state.server = server
    servers.set(key, state)
    return server
  },

  async stopAll() {
    const active = [...servers.values()]
    servers.clear()
    await Promise.all(active.map((item) => item.server.stop(true)))
  },
}

function serveStaticFile(outputDir: string, pathname: string) {
  const safePath = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "")
  const resolved = path.resolve(outputDir, safePath)
  if (!resolved.startsWith(path.resolve(outputDir))) {
    return new Response("Forbidden", { status: 403 })
  }

  if (!existsSync(resolved)) {
    return new Response("Not found", { status: 404 })
  }

  return new Response(Bun.file(resolved), {
    headers: {
      "Content-Type": detectContentType(resolved),
    },
  })
}

function detectContentType(filepath: string) {
  if (filepath.endsWith(".html")) return "text/html; charset=utf-8"
  if (filepath.endsWith(".css")) return "text/css; charset=utf-8"
  if (filepath.endsWith(".js")) return "application/javascript; charset=utf-8"
  if (filepath.endsWith(".json")) return "application/json; charset=utf-8"
  return "text/plain; charset=utf-8"
}
