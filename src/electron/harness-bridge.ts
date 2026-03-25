import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process"
import { existsSync } from "node:fs"
import path from "node:path"
import { JsonRpcClient } from "./jsonrpc-client"
import type { HarnessEvent, HarnessRequestMap, HarnessMethod } from "./types"

type BridgeState = "idle" | "starting" | "ready" | "error"

export class HarnessBridge {
  private child: ChildProcessWithoutNullStreams | undefined
  private client: JsonRpcClient | undefined
  private state: BridgeState = "idle"
  private workspace = process.cwd()
  private readonly eventHandlers = new Set<(event: HarnessEvent) => void>()
  private stderrBuffer = ""

  getWorkspace() {
    return this.workspace
  }

  subscribe(handler: (event: HarnessEvent) => void) {
    this.eventHandlers.add(handler)
    return () => this.eventHandlers.delete(handler)
  }

  async ensureStarted() {
    if (this.state === "ready" && this.client) return
    await this.startChild()
  }

  async setWorkspace(directory: string) {
    this.workspace = directory
    await this.restartChild()
  }

  async reconnect() {
    await this.restartChild()
  }

  async request<K extends HarnessMethod>(method: K, params?: HarnessRequestMap[K]["params"]) {
    await this.ensureStarted()
    if (!this.client) throw new Error("Harness bridge unavailable")
    const timeoutMs = this.timeoutForMethod(method)
    return (await this.client.request(
      this.toRpcMethod(method),
      (params ?? {}) as Record<string, unknown>,
      timeoutMs,
    )) as HarnessRequestMap[K]["result"]
  }

  dispose() {
    this.client?.close()
    this.child?.kill()
    this.client = undefined
    this.child = undefined
    this.state = "idle"
  }

  private async restartChild() {
    this.dispose()
    await this.startChild()
  }

  private async startChild() {
    this.state = "starting"
    this.stderrBuffer = ""

    const command = this.resolveHarnessCommand(this.workspace)
    const child = spawn(command.bin, command.args, {
      cwd: process.cwd(),
      stdio: "pipe",
      env: {
        ...process.env,
        FORCE_COLOR: "0",
      },
    })

    child.stderr.on("data", (chunk: Buffer | string) => {
      this.stderrBuffer = `${this.stderrBuffer}${chunk.toString()}`.slice(-4000)
    })

    child.on("error", (error) => {
      this.state = "error"
      this.emit({ method: "harness.crash", params: { message: error.message } })
    })

    child.on("exit", (code, signal) => {
      if (this.state === "idle") return
      this.state = "error"
      this.emit({
        method: "harness.crash",
        params: {
          code: code ?? -1,
          signal: signal ?? "unknown",
          stderr: this.stderrBuffer,
        },
      })
    })

    this.child = child
    this.client = new JsonRpcClient(child.stdin, child.stdout, child.stderr)
    this.client.onNotification((method, params) => {
      this.emit({ method, params })
    })

    try {
      await this.client.request("initialize", {}, 10000)
      this.state = "ready"
    } catch (error) {
      this.state = "error"
      this.emit({
        method: "harness.crash",
        params: {
          message: error instanceof Error ? error.message : String(error),
          stderr: this.stderrBuffer,
        },
      })
      throw error
    }
  }

  private emit(event: HarnessEvent) {
    for (const handler of this.eventHandlers) {
      handler(event)
    }
  }

  private resolveHarnessCommand(workspace: string) {
    const home = process.env.HOME ?? ""
    const bunCandidates = [
      process.env.BUN_BINARY,
      process.env.BUN,
      path.join(home, ".bun", "bin", "bun"),
      "/opt/homebrew/bin/bun",
      "/usr/local/bin/bun",
      "bun",
    ].filter((value): value is string => Boolean(value))

    const bunPath = bunCandidates.find((candidate) => candidate === "bun" || existsSync(candidate)) ?? "bun"
    return {
      bin: bunPath,
      args: ["run", "--conditions=browser", "./src/index.ts", "harness", "--cwd", workspace],
    }
  }

  private toRpcMethod(method: HarnessMethod) {
    switch (method) {
      case "initialize":
        return "initialize"
      case "threadList":
        return "thread.list"
      case "threadCreate":
        return "thread.create"
      case "threadGet":
        return "thread.get"
      case "turnStart":
        return "turn.start"
      case "turnCancel":
        return "turn.cancel"
      case "approvalRespond":
        return "approval.respond"
      case "setWorkspace":
        return "initialize"
    }
  }

  private timeoutForMethod(method: HarnessMethod) {
    switch (method) {
      case "initialize":
      case "threadList":
      case "threadCreate":
      case "threadGet":
      case "setWorkspace":
      case "reconnect":
        return 10000
      case "approvalRespond":
      case "turnCancel":
        return 15000
      case "turnStart":
        return 30000
      default:
        return 15000
    }
  }
}
