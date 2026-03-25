import { createInterface } from "readline"
import { Log } from "@/util/log"
import type { Protocol } from "./protocol"

export namespace JsonRpc {
  const log = Log.create({ service: "harness-jsonrpc" })

  export const ErrorCode = {
    // Standard JSON-RPC
    ParseError: -32700,
    InvalidRequest: -32600,
    MethodNotFound: -32601,
    InvalidParams: -32602,
    InternalError: -32603,
    // Custom
    ThreadNotFound: -32001,
    TurnBusy: -32002,
    TurnNotFound: -32003,
    ApprovalNotFound: -32004,
  } as const

  export function success(id: string | number, result: unknown): Protocol.JsonRpcResponse {
    return { jsonrpc: "2.0", id, result }
  }

  export function error(
    id: string | number,
    code: number,
    message: string,
    data?: unknown,
  ): Protocol.JsonRpcResponse {
    return { jsonrpc: "2.0", id, error: { code, message, data } }
  }

  export function notification(method: string, params?: Record<string, unknown>): Protocol.JsonRpcNotification {
    return { jsonrpc: "2.0", method, params }
  }

  export interface Transport {
    onMessage(handler: (msg: Protocol.JsonRpcRequest) => void): void
    send(msg: Protocol.JsonRpcResponse | Protocol.JsonRpcNotification): void
    close(): void
  }

  export function stdio(): Transport {
    const rl = createInterface({ input: process.stdin })
    let handler: ((msg: Protocol.JsonRpcRequest) => void) | undefined

    rl.on("line", (line) => {
      if (!line.trim()) return
      try {
        const parsed = JSON.parse(line)
        if (!handler) {
          log.warn("received message but no handler registered", { method: parsed.method })
          return
        }
        handler(parsed)
      } catch (e) {
        log.error("failed to parse JSON-RPC message", { error: e, line })
        const resp = error(0, ErrorCode.ParseError, "Parse error")
        write(resp)
      }
    })

    function write(msg: Protocol.JsonRpcResponse | Protocol.JsonRpcNotification) {
      const serialized = JSON.stringify(msg) + "\n"
      process.stdout.write(serialized)
    }

    return {
      onMessage(cb) {
        handler = cb
      },
      send: write,
      close() {
        rl.close()
      },
    }
  }
}
