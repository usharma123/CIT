import { createStore } from "solid-js/store"
import type { HarnessEvent, HarnessThread } from "../types"

export type ViewMode = "thread" | "automations" | "skills"

export interface TimelineItem {
  itemId: string
  threadId: string
  turnId: string
  type: string
  content: string
  started: boolean
  completed: boolean
  interrupted: boolean
  data?: Record<string, unknown>
}

export interface AppState {
  workspace: string
  selectedThreadID?: string
  threads: HarnessThread[]
  view: ViewMode
  timelines: Record<string, TimelineItem[]>
  activeTurns: Record<string, { turnId: string; status: string }>
  pendingApprovals: Record<string, { requestId: string; itemId: string; threadId: string; turnId: string }>
  processedEventKeys: Record<string, boolean>
  status: "idle" | "loading" | "ready" | "error"
  banner?: string
  warning?: string
}

export function createInitialState(workspace: string): AppState {
  return {
    workspace,
    selectedThreadID: undefined,
    threads: [],
    view: "thread",
    timelines: {},
    activeTurns: {},
    pendingApprovals: {},
    processedEventKeys: {},
    status: "idle",
  }
}

export function eventKey(event: HarnessEvent) {
  const params = event.params ?? {}
  const item = (params.item ?? {}) as Record<string, unknown>
  const itemId =
    typeof params.itemId === "string" ? params.itemId : typeof item.itemId === "string" ? item.itemId : "-"
  const turnId =
    typeof params.turnId === "string" ? params.turnId : typeof item.turnId === "string" ? item.turnId : "-"
  const threadId =
    typeof params.threadId === "string" ? params.threadId : typeof item.threadId === "string" ? item.threadId : "-"
  const timestamp = typeof params.timestamp === "number" ? params.timestamp : "-"
  return [event.method, threadId, turnId, itemId, timestamp].join("|")
}

export function reduceEvent(state: AppState, event: HarnessEvent): AppState {
  const params = event.params ?? {}
  const notification = typeof params.notification === "string" ? params.notification : event.method
  const shouldDedupe = notification !== "item.delta"
  const key = shouldDedupe ? eventKey(event) : ""
  if (shouldDedupe && state.processedEventKeys[key]) return state
  const next = {
    ...state,
    processedEventKeys: shouldDedupe
      ? { ...state.processedEventKeys, [key]: true }
      : state.processedEventKeys,
  }

  if (notification === "thread.created") {
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    const title = typeof params.title === "string" ? params.title : "New Thread"
    if (!threadId) return next
    if (next.threads.find((thread) => thread.threadId === threadId)) return next
    next.threads = [
      {
        threadId,
        title,
        directory: state.workspace,
        time: {
          created: Date.now(),
          updated: Date.now(),
        },
      },
      ...next.threads,
    ]
    return next
  }

  if (notification === "turn.started") {
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    const turnId = typeof params.turnId === "string" ? params.turnId : ""
    if (!threadId || !turnId) return next
    next.activeTurns = { ...next.activeTurns, [threadId]: { turnId, status: "running" } }
    return next
  }

  if (notification === "item.started") {
    const item = (params.item ?? {}) as Record<string, unknown>
    const itemId = typeof item.itemId === "string" ? item.itemId : ""
    const threadId = typeof item.threadId === "string" ? item.threadId : ""
    const turnId = typeof item.turnId === "string" ? item.turnId : ""
    const type = typeof item.type === "string" ? item.type : "assistant_message"
    if (!itemId || !threadId || !turnId) return next
    const timeline = next.timelines[threadId] ?? []
    if (timeline.find((existing) => existing.itemId === itemId)) return next

    const optimisticIndex = timeline.findIndex(
      (existing) =>
        existing.itemId.startsWith("optimistic:") &&
        existing.turnId === turnId &&
        existing.type === type &&
        existing.completed === false,
    )

    const created: TimelineItem = {
      itemId,
      threadId,
      turnId,
      type,
      content: "",
      started: true,
      completed: false,
      interrupted: false,
      data: (item.data as Record<string, unknown>) ?? {},
    }

    if (optimisticIndex >= 0) {
      timeline.splice(optimisticIndex, 1, created)
    } else {
      timeline.push(created)
    }
    next.timelines = { ...next.timelines, [threadId]: timeline }
    return next
  }

  if (notification === "item.delta") {
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    const itemId = typeof params.itemId === "string" ? params.itemId : ""
    const delta = typeof params.delta === "string" ? params.delta : ""
    if (!threadId || !itemId) return next
    const timeline = [...(next.timelines[threadId] ?? [])]
    const index = timeline.findIndex((item) => item.itemId === itemId)
    if (index < 0) return next
    const current = timeline[index]
    timeline[index] = {
      ...current,
      content: `${current.content}${delta}`,
      data: {
        ...(current.data ?? {}),
        ...(params.data as Record<string, unknown> | undefined),
      },
    }
    next.timelines = { ...next.timelines, [threadId]: timeline }
    return next
  }

  if (notification === "item.completed") {
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    const itemId = typeof params.itemId === "string" ? params.itemId : ""
    if (!threadId || !itemId) return next
    const timeline = [...(next.timelines[threadId] ?? [])]
    const index = timeline.findIndex((item) => item.itemId === itemId)
    if (index < 0) return next
    timeline[index] = {
      ...timeline[index],
      completed: true,
      data: {
        ...(timeline[index].data ?? {}),
        ...(params.data as Record<string, unknown> | undefined),
      },
    }
    next.timelines = { ...next.timelines, [threadId]: timeline }
    return next
  }

  if (notification === "approval.requested") {
    const requestId = typeof params.requestId === "string" ? params.requestId : ""
    const itemId = typeof params.itemId === "string" ? params.itemId : ""
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    const turnId = typeof params.turnId === "string" ? params.turnId : ""
    if (!requestId || !itemId || !threadId || !turnId) return next
    next.pendingApprovals = {
      ...next.pendingApprovals,
      [requestId]: { requestId, itemId, threadId, turnId },
    }
    return next
  }

  if (notification === "turn.completed" || notification === "turn.error") {
    const threadId = typeof params.threadId === "string" ? params.threadId : ""
    if (!threadId) return next
    const updated = { ...next.activeTurns }
    delete updated[threadId]
    next.activeTurns = updated
    return next
  }

  if (notification === "harness.crash") {
    const message = typeof params.message === "string" ? params.message : "Harness process stopped"
    const stderr = typeof params.stderr === "string" ? params.stderr.trim() : ""
    const detail = stderr ? `${message}: ${stderr.slice(0, 400)}` : message
    next.banner = `${detail}. Use Reconnect to restore the session.`
    next.status = "error"
    return next
  }

  return next
}

export function createAppStore(workspace: string) {
  const [state, setState] = createStore<AppState>(createInitialState(workspace))

  const api = {
    hardReset(workspacePath: string) {
      setState(() => createInitialState(workspacePath))
    },

    setWorkspace(workspacePath: string) {
      setState("workspace", workspacePath)
    },

    setStatus(status: AppState["status"]) {
      setState("status", status)
    },

    setBanner(text?: string) {
      setState("banner", text)
    },

    setWarning(text?: string) {
      setState("warning", text)
    },

    setThreads(threads: HarnessThread[]) {
      setState("threads", threads)
    },

    setSelectedThread(threadId?: string) {
      setState("selectedThreadID", threadId)
      if (threadId) setState("view", "thread")
    },

    setView(mode: ViewMode) {
      setState("view", mode)
    },

    setTimeline(threadId: string, timeline: TimelineItem[]) {
      setState("timelines", threadId, timeline)
    },

    addOptimisticUserItem(threadId: string, text: string) {
      const item: TimelineItem = {
        itemId: `optimistic:${Date.now()}`,
        threadId,
        turnId: "pending",
        type: "user_message",
        content: text,
        started: true,
        completed: false,
        interrupted: false,
      }
      const timeline = [...(state.timelines[threadId] ?? []), item]
      setState("timelines", threadId, timeline)
    },

    markOptimisticTurn(threadId: string, turnId: string) {
      const timeline = [...(state.timelines[threadId] ?? [])]
      const index = timeline.findIndex((item) => item.turnId === "pending" && item.itemId.startsWith("optimistic:"))
      if (index < 0) return
      timeline[index] = { ...timeline[index], turnId }
      setState("timelines", threadId, timeline)
    },

    apply(event: HarnessEvent) {
      setState((current) => reduceEvent(current, event))
    },
  }

  return { state, api }
}
