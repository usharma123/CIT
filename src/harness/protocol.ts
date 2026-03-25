import z from "zod"

export namespace Protocol {
  // ─── JSON-RPC 2.0 Envelope ───────────────────────────────────────────

  export const JsonRpcRequest = z.object({
    jsonrpc: z.literal("2.0"),
    id: z.union([z.string(), z.number()]),
    method: z.string(),
    params: z.record(z.string(), z.any()).optional(),
  })
  export type JsonRpcRequest = z.infer<typeof JsonRpcRequest>

  export const JsonRpcNotification = z.object({
    jsonrpc: z.literal("2.0"),
    method: z.string(),
    params: z.record(z.string(), z.any()).optional(),
  })
  export type JsonRpcNotification = z.infer<typeof JsonRpcNotification>

  export const JsonRpcResponse = z.object({
    jsonrpc: z.literal("2.0"),
    id: z.union([z.string(), z.number()]),
    result: z.any().optional(),
    error: z
      .object({
        code: z.number(),
        message: z.string(),
        data: z.any().optional(),
      })
      .optional(),
  })
  export type JsonRpcResponse = z.infer<typeof JsonRpcResponse>

  // ─── Thread (maps from Session.Info) ─────────────────────────────────

  export const Thread = z.object({
    threadId: z.string(),
    title: z.string(),
    directory: z.string(),
    time: z.object({
      created: z.number(),
      updated: z.number(),
    }),
  })
  export type Thread = z.infer<typeof Thread>

  // ─── Turn ────────────────────────────────────────────────────────────

  export const TurnStatus = z.enum(["running", "completed", "cancelled", "error"])
  export type TurnStatus = z.infer<typeof TurnStatus>

  export const Turn = z.object({
    turnId: z.string(),
    threadId: z.string(),
    status: TurnStatus,
    time: z.object({
      started: z.number(),
      completed: z.number().optional(),
    }),
  })
  export type Turn = z.infer<typeof Turn>

  // ─── Items ───────────────────────────────────────────────────────────

  export const ItemType = z.enum([
    "user_message",
    "assistant_message",
    "tool_exec",
    "tool_log",
    "approval",
    "artifact",
  ])
  export type ItemType = z.infer<typeof ItemType>

  export const Item = z.object({
    itemId: z.string(),
    threadId: z.string(),
    turnId: z.string(),
    type: ItemType,
    data: z.record(z.string(), z.any()).optional(),
  })
  export type Item = z.infer<typeof Item>

  // ─── Method Params ───────────────────────────────────────────────────

  export const InitializeParams = z
    .object({
      clientInfo: z
        .object({
          name: z.string(),
          version: z.string().optional(),
        })
        .optional(),
    })
    .optional()
  export type InitializeParams = z.infer<typeof InitializeParams>

  export const ThreadCreateParams = z.object({
    title: z.string().optional(),
    directory: z.string().optional(),
  })
  export type ThreadCreateParams = z.infer<typeof ThreadCreateParams>

  export const ThreadListParams = z.object({}).optional()
  export type ThreadListParams = z.infer<typeof ThreadListParams>

  export const ThreadGetParams = z.object({
    threadId: z.string(),
  })
  export type ThreadGetParams = z.infer<typeof ThreadGetParams>

  export const TurnStartParams = z.object({
    threadId: z.string(),
    input: z.array(
      z.discriminatedUnion("type", [
        z.object({ type: z.literal("text"), text: z.string() }),
        z.object({ type: z.literal("file"), url: z.string(), filename: z.string(), mime: z.string() }),
      ]),
    ),
    model: z
      .object({
        providerID: z.string(),
        modelID: z.string(),
      })
      .optional(),
    agent: z.string().optional(),
  })
  export type TurnStartParams = z.infer<typeof TurnStartParams>

  export const ApprovalRespondParams = z.object({
    requestId: z.string(),
    decision: z.enum(["once", "always", "reject"]),
  })
  export type ApprovalRespondParams = z.infer<typeof ApprovalRespondParams>

  export const TurnCancelParams = z.object({
    threadId: z.string(),
  })
  export type TurnCancelParams = z.infer<typeof TurnCancelParams>

  // ─── Results ─────────────────────────────────────────────────────────

  export const InitializeResult = z.object({
    version: z.string(),
    capabilities: z.object({
      threads: z.boolean(),
      turns: z.boolean(),
      approvals: z.boolean(),
      streaming: z.boolean(),
      persistence: z.boolean(),
    }),
  })
  export type InitializeResult = z.infer<typeof InitializeResult>

  export const ThreadCreateResult = z.object({
    thread: Thread,
  })
  export type ThreadCreateResult = z.infer<typeof ThreadCreateResult>

  export const ThreadListResult = z.object({
    threads: Thread.array(),
  })
  export type ThreadListResult = z.infer<typeof ThreadListResult>

  export const ThreadGetResult = z.object({
    thread: Thread,
    events: z.array(z.record(z.string(), z.any())),
  })
  export type ThreadGetResult = z.infer<typeof ThreadGetResult>

  export const TurnStartResult = z.object({
    turnId: z.string(),
  })
  export type TurnStartResult = z.infer<typeof TurnStartResult>

  export const ApprovalRespondResult = z.object({
    ok: z.boolean(),
  })
  export type ApprovalRespondResult = z.infer<typeof ApprovalRespondResult>

  export const TurnCancelResult = z.object({
    ok: z.boolean(),
  })
  export type TurnCancelResult = z.infer<typeof TurnCancelResult>

  // ─── Notification types (server → client) ────────────────────────────

  export const Methods = {
    INITIALIZE: "initialize",
    THREAD_CREATE: "thread.create",
    THREAD_LIST: "thread.list",
    THREAD_GET: "thread.get",
    TURN_START: "turn.start",
    APPROVAL_RESPOND: "approval.respond",
    TURN_CANCEL: "turn.cancel",
  } as const

  export const Notifications = {
    THREAD_CREATED: "thread.created",
    TURN_STARTED: "turn.started",
    TURN_COMPLETED: "turn.completed",
    TURN_ERROR: "turn.error",
    ITEM_STARTED: "item.started",
    ITEM_DELTA: "item.delta",
    ITEM_COMPLETED: "item.completed",
    APPROVAL_REQUESTED: "approval.requested",
  } as const
}
