import { Log } from "@/util/log"
import { Session } from "@/session"
import { SessionPrompt } from "@/session/prompt"
import { Instance } from "@/project/instance"
import { Identifier } from "@/id/id"
import { Installation } from "@/installation"
import { Provider } from "@/provider/provider"
import { Agent } from "@/agent/agent"
import { Protocol } from "./protocol"
import { JsonRpc } from "./jsonrpc"
import { ThreadManager } from "./thread-manager"
import { TurnRunner } from "./turn-runner"
import { ApprovalGate } from "./approval-gate"
import { EventLog } from "./event-log"

export namespace HarnessServer {
  const log = Log.create({ service: "harness-server" })

  export function create(config: { directory: string }) {
    const { directory } = config
    const transport = JsonRpc.stdio()

    transport.onMessage(async (msg) => {
      if (!msg.id && msg.id !== 0) {
        // Notification from client (no id) — ignore for now
        return
      }

      try {
        const result = await route(msg.method, msg.params ?? {}, { directory, transport })
        transport.send(JsonRpc.success(msg.id, result))
      } catch (e: any) {
        log.error("method error", { method: msg.method, error: e })
        const code = e.jsonRpcCode ?? JsonRpc.ErrorCode.InternalError
        transport.send(JsonRpc.error(msg.id, code, e.message ?? "Internal error"))
      }
    })

    log.info("harness server started", { directory })

    return { transport }
  }

  async function route(
    method: string,
    params: Record<string, any>,
    ctx: { directory: string; transport: JsonRpc.Transport },
  ): Promise<unknown> {
    switch (method) {
      case Protocol.Methods.INITIALIZE:
        return handleInitialize()
      case Protocol.Methods.THREAD_CREATE:
        return handleThreadCreate(params, ctx)
      case Protocol.Methods.THREAD_LIST:
        return handleThreadList()
      case Protocol.Methods.THREAD_GET:
        return handleThreadGet(params, ctx)
      case Protocol.Methods.TURN_START:
        return handleTurnStart(params, ctx)
      case Protocol.Methods.APPROVAL_RESPOND:
        return handleApprovalRespond(params)
      case Protocol.Methods.TURN_CANCEL:
        return handleTurnCancel(params)
      default:
        throw rpcError(JsonRpc.ErrorCode.MethodNotFound, `Unknown method: ${method}`)
    }
  }

  function handleInitialize(): Protocol.InitializeResult {
    return {
      version: Installation.VERSION,
      capabilities: {
        threads: true,
        turns: true,
        approvals: true,
        streaming: true,
        persistence: true,
      },
    }
  }

  async function handleThreadCreate(
    params: Record<string, any>,
    ctx: { directory: string; transport: JsonRpc.Transport },
  ): Promise<Protocol.ThreadCreateResult> {
    const parsed = Protocol.ThreadCreateParams.parse(params)
    const dir = parsed.directory ?? ctx.directory

    // Create a session (Session ID = Thread ID)
    const session = await Session.create({
      title: parsed.title,
    })

    ThreadManager.createThread(session.id, dir)

    await EventLog.writeMeta(dir, session.id, {
      threadId: session.id,
      title: session.title,
      directory: dir,
      created: session.time.created,
    })

    const threadCreatedEvent = {
      notification: Protocol.Notifications.THREAD_CREATED,
      threadId: session.id,
      title: session.title,
    }
    ctx.transport.send(JsonRpc.notification(Protocol.Notifications.THREAD_CREATED, threadCreatedEvent))
    await EventLog.append(dir, session.id, threadCreatedEvent)

    return {
      thread: {
        threadId: session.id,
        title: session.title,
        directory: dir,
        time: session.time,
      },
    }
  }

  async function handleThreadList(): Promise<Protocol.ThreadListResult> {
    const threads: Protocol.Thread[] = []

    for await (const session of Session.list()) {
      if (!session) continue
      threads.push({
        threadId: session.id,
        title: session.title,
        directory: session.directory,
        time: session.time,
      })
    }

    return { threads }
  }

  async function handleThreadGet(
    params: Record<string, any>,
    ctx: { directory: string },
  ): Promise<Protocol.ThreadGetResult> {
    const parsed = Protocol.ThreadGetParams.parse(params)
    const session = await Session.get(parsed.threadId)
    if (!session) {
      throw rpcError(JsonRpc.ErrorCode.ThreadNotFound, `Thread not found: ${parsed.threadId}`)
    }

    const events = await EventLog.replay(ctx.directory, parsed.threadId)

    return {
      thread: {
        threadId: session.id,
        title: session.title,
        directory: session.directory,
        time: session.time,
      },
      events,
    }
  }

  async function handleTurnStart(
    params: Record<string, any>,
    ctx: { directory: string; transport: JsonRpc.Transport },
  ): Promise<Protocol.TurnStartResult> {
    const parsed = Protocol.TurnStartParams.parse(params)

    const thread = ThreadManager.getThread(parsed.threadId)
    if (!thread) {
      // Try to load from session storage
      const session = await Session.get(parsed.threadId).catch(() => undefined)
      if (!session) {
        throw rpcError(JsonRpc.ErrorCode.ThreadNotFound, `Thread not found: ${parsed.threadId}`)
      }
      ThreadManager.createThread(session.id, session.directory)
    }

    const activeTurn = ThreadManager.getActiveTurn(parsed.threadId)
    if (activeTurn) {
      throw rpcError(JsonRpc.ErrorCode.TurnBusy, `Thread ${parsed.threadId} already has an active turn`)
    }

    const turnId = Identifier.ascending("session") // reuse session prefix for turn IDs

    // Start turn asynchronously — return turnId immediately
    TurnRunner.start({
      turnId,
      threadId: parsed.threadId,
      sessionID: parsed.threadId, // session ID = thread ID
      directory: ctx.directory,
      parts: parsed.input,
      model: parsed.model,
      agent: parsed.agent,
      transport: ctx.transport,
    }).catch((e) => {
      log.error("turn runner error", { error: e, turnId })
    })

    return { turnId }
  }

  async function handleApprovalRespond(params: Record<string, any>): Promise<Protocol.ApprovalRespondResult> {
    const parsed = Protocol.ApprovalRespondParams.parse(params)
    return ApprovalGate.handleRespond(parsed)
  }

  async function handleTurnCancel(params: Record<string, any>): Promise<Protocol.TurnCancelResult> {
    const parsed = Protocol.TurnCancelParams.parse(params)
    SessionPrompt.cancel(parsed.threadId) // session ID = thread ID
    return { ok: true }
  }

  function rpcError(code: number, message: string): Error & { jsonRpcCode: number } {
    const err = new Error(message) as Error & { jsonRpcCode: number }
    err.jsonRpcCode = code
    return err
  }
}
