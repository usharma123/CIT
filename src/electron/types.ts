export interface HarnessThread {
  threadId: string
  title: string
  directory: string
  time: {
    created: number
    updated: number
  }
}

export interface HarnessInitializeResult {
  version: string
  capabilities: {
    threads: boolean
    turns: boolean
    approvals: boolean
    streaming: boolean
    persistence: boolean
  }
}

export interface HarnessThreadWithEvents {
  thread: HarnessThread
  events: Record<string, unknown>[]
}

export interface StartTurnInput {
  threadId: string
  text: string
  model?: {
    providerID: string
    modelID: string
  }
  agent?: string
}

export interface ApprovalInput {
  requestId: string
  decision: "once" | "always" | "reject"
}

export interface HarnessRequestMap {
  initialize: { params?: Record<string, never>; result: HarnessInitializeResult }
  setWorkspace: { params: { directory: string }; result: { ok: boolean; directory: string } }
  reconnect: { params?: Record<string, never>; result: { ok: boolean } }
  threadList: { params?: Record<string, never>; result: { threads: HarnessThread[] } }
  threadCreate: { params: { title?: string; directory?: string }; result: { thread: HarnessThread } }
  threadGet: { params: { threadId: string }; result: HarnessThreadWithEvents }
  turnStart: {
    params: {
      threadId: string
      input: Array<{ type: "text"; text: string }>
      model?: { providerID: string; modelID: string }
      agent?: string
    }
    result: { turnId: string }
  }
  turnCancel: { params: { threadId: string }; result: { ok: boolean } }
  approvalRespond: { params: ApprovalInput; result: { ok: boolean } }
}

export type HarnessMethod = keyof HarnessRequestMap

export interface HarnessEvent {
  method: string
  params: Record<string, unknown>
}

export interface BootstrapHarnessAPI {
  initialize(): Promise<HarnessInitializeResult>
  setWorkspace(directory: string): Promise<void>
  reconnect(): Promise<void>
  listThreads(): Promise<HarnessThread[]>
  createThread(input: { title?: string; directory?: string }): Promise<HarnessThread>
  getThread(threadId: string): Promise<HarnessThreadWithEvents>
  startTurn(input: StartTurnInput): Promise<{ turnId: string }>
  cancelTurn(threadId: string): Promise<{ ok: boolean }>
  respondApproval(input: ApprovalInput): Promise<{ ok: boolean }>
  subscribe(handler: (event: HarnessEvent) => void): () => void
}
