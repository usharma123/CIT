import { Log } from "@/util/log"
import type { Protocol } from "./protocol"

export namespace ThreadManager {
  const log = Log.create({ service: "harness-thread-manager" })

  export interface ThreadState {
    threadId: string
    directory: string
    activeTurnId: string | undefined
    model:
      | {
          providerID: string
          modelID: string
        }
      | undefined
    agent: string | undefined
  }

  export interface TurnState {
    turnId: string
    threadId: string
    messageID: string | undefined
    status: Protocol.TurnStatus
    items: Map<string, Protocol.Item>
    unsubscribe: (() => void) | undefined
    timeStarted: number
    timeCompleted: number | undefined
  }

  const threads = new Map<string, ThreadState>()
  const turns = new Map<string, TurnState>()

  export function createThread(threadId: string, directory: string): ThreadState {
    const state: ThreadState = {
      threadId,
      directory,
      activeTurnId: undefined,
      model: undefined,
      agent: undefined,
    }
    threads.set(threadId, state)
    log.info("thread created", { threadId })
    return state
  }

  export function getThread(threadId: string): ThreadState | undefined {
    return threads.get(threadId)
  }

  export function listThreads(): ThreadState[] {
    return Array.from(threads.values())
  }

  export function removeThread(threadId: string) {
    threads.delete(threadId)
  }

  export function startTurn(turnId: string, threadId: string): TurnState {
    const thread = threads.get(threadId)
    if (thread) {
      thread.activeTurnId = turnId
    }
    const state: TurnState = {
      turnId,
      threadId,
      messageID: undefined,
      status: "running",
      items: new Map(),
      unsubscribe: undefined,
      timeStarted: Date.now(),
      timeCompleted: undefined,
    }
    turns.set(turnId, state)
    log.info("turn started", { turnId, threadId })
    return state
  }

  export function getTurn(turnId: string): TurnState | undefined {
    return turns.get(turnId)
  }

  export function getActiveTurn(threadId: string): TurnState | undefined {
    const thread = threads.get(threadId)
    if (!thread?.activeTurnId) return undefined
    return turns.get(thread.activeTurnId)
  }

  export function completeTurn(turnId: string, status: Protocol.TurnStatus) {
    const turn = turns.get(turnId)
    if (!turn) return
    turn.status = status
    turn.timeCompleted = Date.now()
    if (turn.unsubscribe) {
      turn.unsubscribe()
      turn.unsubscribe = undefined
    }
    const thread = threads.get(turn.threadId)
    if (thread?.activeTurnId === turnId) {
      thread.activeTurnId = undefined
    }
    log.info("turn completed", { turnId, status })
  }
}
