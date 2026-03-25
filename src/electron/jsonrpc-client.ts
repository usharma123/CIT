import type { Writable } from "node:stream"
import { EventEmitter } from "node:events"

interface PendingRequest {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
  timeout: NodeJS.Timeout
}

interface JsonRpcResponse {
  jsonrpc: "2.0"
  id: string | number
  result?: unknown
  error?: {
    code: number
    message: string
    data?: unknown
  }
}

interface JsonRpcNotification {
  jsonrpc: "2.0"
  method: string
  params?: Record<string, unknown>
}

export class JsonRpcClient {
  private nextID = 1
  private readonly pending = new Map<string | number, PendingRequest>()
  private readonly emitter = new EventEmitter()
  private closed = false
  private buffer = ""

  constructor(
    private readonly stdin: Writable,
    stdout: NodeJS.ReadableStream,
    stderr: NodeJS.ReadableStream,
  ) {
    stdout.on("data", (chunk: Buffer | string) => {
      this.consume(chunk.toString())
    })
    stderr.on("data", () => {
      // Ignore harness stderr in MVP.
    })
    stdout.on("end", () => this.close(new Error("Harness stdout closed")))
    stdout.on("error", (error) => this.close(error instanceof Error ? error : new Error(String(error))))
  }

  onNotification(handler: (method: string, params: Record<string, unknown>) => void) {
    this.emitter.on("notification", handler)
    return () => this.emitter.off("notification", handler)
  }

  async request(method: string, params?: Record<string, unknown>, timeoutMs = 120000) {
    if (this.closed) throw new Error("Harness client is closed")

    const id = this.nextID++
    const payload = {
      jsonrpc: "2.0" as const,
      id,
      method,
      params,
    }

    const message = JSON.stringify(payload) + "\n"
    this.stdin.write(message)

    return await new Promise<unknown>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`Request timed out: ${method}`))
      }, timeoutMs)

      this.pending.set(id, { resolve, reject, timeout })
    })
  }

  close(error?: Error) {
    if (this.closed) return
    this.closed = true
    for (const [id, pending] of this.pending.entries()) {
      clearTimeout(pending.timeout)
      pending.reject(error ?? new Error("Harness connection closed"))
      this.pending.delete(id)
    }
  }

  private consume(chunk: string) {
    this.buffer += chunk
    let index = this.buffer.indexOf("\n")

    while (index !== -1) {
      const line = this.buffer.slice(0, index).trim()
      this.buffer = this.buffer.slice(index + 1)
      index = this.buffer.indexOf("\n")

      if (!line) continue
      this.handleLine(line)
    }
  }

  private handleLine(line: string) {
    let message: JsonRpcResponse | JsonRpcNotification
    try {
      message = JSON.parse(line)
    } catch {
      return
    }

    if ("id" in message) {
      const pending = this.pending.get(message.id)
      if (!pending) return
      clearTimeout(pending.timeout)
      this.pending.delete(message.id)
      if (message.error) {
        pending.reject(new Error(message.error.message))
        return
      }
      pending.resolve(message.result)
      return
    }

    const params = message.params ?? {}
    this.emitter.emit("notification", message.method, params)
  }
}
