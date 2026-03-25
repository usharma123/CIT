import { For, Show, createEffect, createMemo, createSignal, onCleanup, onMount } from "solid-js"
import type { HarnessEvent, HarnessThread } from "../types"
import { createAppStore, type TimelineItem } from "./store"

const STORAGE_KEY = "bootstrap-electron-ui"

function relativeTime(timestamp: number) {
  const delta = Date.now() - timestamp
  if (delta < 60_000) return "just now"
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h ago`
  return `${Math.floor(delta / 86_400_000)}d ago`
}

export function App() {
  const initialWorkspace = "/"
  const { state, api } = createAppStore(initialWorkspace)
  const [composerText, setComposerText] = createSignal("")
  const [workspaceInput, setWorkspaceInput] = createSignal(initialWorkspace)

  const selectedThread = createMemo(() =>
    state.threads.find((thread) => thread.threadId === state.selectedThreadID),
  )
  const selectedTimeline = createMemo(() => {
    if (!state.selectedThreadID) return [] as TimelineItem[]
    return state.timelines[state.selectedThreadID] ?? []
  })

  const isTurnActive = createMemo(() => {
    if (!state.selectedThreadID) return false
    return Boolean(state.activeTurns[state.selectedThreadID])
  })

  function harness() {
    return window.bootstrapHarness
  }

  async function hydrateThread(threadId: string) {
    const bridge = harness()
    if (!bridge) throw new Error("Preload bridge unavailable")

    const payload = await bridge.getThread(threadId)
    const replay: HarnessEvent[] = payload.events.map((event) => {
      const notification = String(event.notification ?? "")
      return {
        method: notification,
        params: event,
      }
    })

    api.setTimeline(threadId, [])
    for (const event of replay) {
      const notification = event.method
      // Replay is historical; only live notifications should control active turn state.
      if (notification === "turn.started" || notification === "turn.completed" || notification === "turn.error") {
        continue
      }
      api.apply(event)
    }
  }

  async function bootstrap() {
    api.setStatus("loading")
    const bridge = harness()
    if (!bridge) throw new Error("Preload bridge unavailable")

    const persisted = localStorage.getItem(STORAGE_KEY)
    if (persisted) {
      try {
        const decoded = JSON.parse(persisted) as {
          workspace?: string
          selectedThreadID?: string
          view?: "thread" | "automations" | "skills"
        }
        if (decoded.workspace) {
          setWorkspaceInput(decoded.workspace)
          api.setWorkspace(decoded.workspace)
          await bridge.setWorkspace(decoded.workspace)
        }
        if (decoded.view) api.setView(decoded.view)
        if (decoded.selectedThreadID) api.setSelectedThread(decoded.selectedThreadID)
      } catch {
        // Ignore invalid persisted state.
      }
    }

    await bridge.initialize()
    const threads = await bridge.listThreads()
    api.setThreads(threads)

    const targetThread =
      (state.selectedThreadID && threads.find((thread) => thread.threadId === state.selectedThreadID)?.threadId) ||
      threads[0]?.threadId

    if (targetThread) {
      api.setSelectedThread(targetThread)
      await hydrateThread(targetThread)
    }

    api.setStatus("ready")
  }

  async function handleSetWorkspace() {
    const target = workspaceInput().trim()
    if (!target) return
    const bridge = harness()
    if (!bridge) return

    try {
      await bridge.setWorkspace(target)
      api.hardReset(target)
      const threads = await bridge.listThreads()
      api.setThreads(threads)
      if (threads[0]) {
        api.setSelectedThread(threads[0].threadId)
        await hydrateThread(threads[0].threadId)
      }
      api.setStatus("ready")
    } catch (error) {
      api.setStatus("error")
      api.setWarning(error instanceof Error ? error.message : "Failed to set workspace")
    }
  }

  async function handleCreateThread() {
    const bridge = harness()
    if (!bridge) return

    try {
      const created = await bridge.createThread({ directory: state.workspace })
      api.setThreads([created, ...state.threads.filter((thread) => thread.threadId !== created.threadId)])
      api.setSelectedThread(created.threadId)
      api.setTimeline(created.threadId, [])
    } catch (error) {
      api.setWarning(error instanceof Error ? error.message : "Failed to create thread")
    }
  }

  async function handleSelectThread(thread: HarnessThread) {
    try {
      api.setSelectedThread(thread.threadId)
      await hydrateThread(thread.threadId)
    } catch (error) {
      api.setWarning(error instanceof Error ? error.message : "Failed to load thread")
    }
  }

  async function handleSend() {
    const text = composerText().trim()
    if (!text || isTurnActive()) return

    const bridge = harness()
    if (!bridge) {
      api.setWarning("Preload bridge unavailable")
      return
    }

    let threadId = state.selectedThreadID
    if (!threadId) {
      try {
        const created = await bridge.createThread({ directory: state.workspace })
        api.setThreads([created, ...state.threads.filter((thread) => thread.threadId !== created.threadId)])
        api.setSelectedThread(created.threadId)
        api.setTimeline(created.threadId, [])
        threadId = created.threadId
      } catch (error) {
        api.setWarning(error instanceof Error ? error.message : "Failed to create thread")
        return
      }
    }

    api.addOptimisticUserItem(threadId, text)
    setComposerText("")

    try {
      const started = await bridge.startTurn({ threadId, text })
      api.markOptimisticTurn(threadId, started.turnId)
    } catch (error) {
      api.setWarning(error instanceof Error ? error.message : "Failed to start turn")
    }
  }

  async function handleCancel() {
    if (!state.selectedThreadID || !isTurnActive()) return
    const bridge = harness()
    if (!bridge) return
    await bridge.cancelTurn(state.selectedThreadID)
  }

  async function handleApprovalDecision(requestId: string, decision: "once" | "always" | "reject") {
    try {
      const bridge = harness()
      if (!bridge) return
      await bridge.respondApproval({ requestId, decision })
    } catch (error) {
      api.setWarning(error instanceof Error ? error.message : "Failed to send approval decision")
    }
  }

  async function handleReconnect() {
    try {
      const bridge = harness()
      if (!bridge) {
        api.setBanner("Preload bridge unavailable. Restart the app.")
        return
      }
      api.setStatus("loading")
      api.setBanner(undefined)
      await bridge.reconnect()
      await bootstrap()
    } catch (error) {
      api.setStatus("error")
      api.setBanner(error instanceof Error ? error.message : "Reconnect failed")
    }
  }

  onMount(() => {
    if (!harness()) {
      api.setStatus("error")
      api.setBanner("Desktop bridge failed to load. Restart the app.")
      return
    }

    bootstrap().catch((error) => {
      api.setStatus("error")
      api.setBanner(error instanceof Error ? error.message : "Failed to initialize app")
    })

    const unsubscribe = harness()!.subscribe((event) => {
      api.apply(event)
    })

    onCleanup(() => unsubscribe())
  })

  createEffect(() => {
    const payload = {
      workspace: state.workspace,
      selectedThreadID: state.selectedThreadID,
      view: state.view,
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  })

  return (
    <div class="app-shell">
      <aside class="sidebar">
        <button class="action primary" onClick={handleCreateThread}>
          New thread
        </button>
        <button class="action" onClick={() => api.setView("automations")}>
          Automations
        </button>
        <button class="action" onClick={() => api.setView("skills")}>
          Skills
        </button>

        <div class="workspace-picker">
          <input
            value={workspaceInput()}
            onInput={(event: InputEvent & { currentTarget: HTMLInputElement }) =>
              setWorkspaceInput(event.currentTarget.value)
            }
            placeholder="Workspace directory"
          />
          <button class="ghost" onClick={() => void handleSetWorkspace()}>
            Set workspace
          </button>
        </div>

        <div class="thread-group-label">Threads</div>
        <div class="thread-list">
          <For each={state.threads}>
            {(thread) => (
              <button
                class={`thread-row ${thread.threadId === state.selectedThreadID && state.view === "thread" ? "active" : ""}`}
                onClick={() => handleSelectThread(thread)}
              >
                <span class="thread-title">{thread.title || "Untitled"}</span>
                <span class="thread-time">{relativeTime(thread.time.updated)}</span>
              </button>
            )}
          </For>
        </div>
      </aside>

      <main class="conversation-pane">
        <header class="pane-header">
          <div class="pane-title">
            <Show when={state.view === "thread"} fallback={<span>{state.view === "automations" ? "Automations" : "Skills"}</span>}>
              <span>{selectedThread()?.title || "Conversation"}</span>
            </Show>
          </div>
          <div class="header-actions">
            <button class="ghost">Open</button>
            <button class="ghost">Commit</button>
          </div>
        </header>

        <Show when={!state.banner}>
          <Show when={state.view === "thread"} fallback={<PlaceholderView mode={state.view} />}>
            <section class="timeline">
              <Show
                when={selectedTimeline().length > 0}
                fallback={
                  <div class="empty-state">
                    <div class="empty-icon">+</div>
                    <div>Let&apos;s build {selectedThread()?.title || "your project"}</div>
                  </div>
                }
              >
                <For each={selectedTimeline()}>
                  {(item) => <TimelineCard item={item} onApproval={handleApprovalDecision} />}
                </For>
              </Show>
            </section>
          </Show>
        </Show>

        <Show when={state.banner}>
          <div class="banner error">
            <div class="banner-content">{state.banner}</div>
            <div class="banner-actions">
              <button class="ghost" onClick={() => void handleReconnect()}>
                Reconnect
              </button>
            </div>
          </div>
        </Show>
        <Show when={state.warning}>
          <div class="banner warning">{state.warning}</div>
        </Show>

        <Show when={state.view === "thread"}>
          <footer class="composer">
            <textarea
              autofocus
              value={composerText()}
              onInput={(event: InputEvent & { currentTarget: HTMLTextAreaElement }) =>
                setComposerText(event.currentTarget.value)
              }
              disabled={isTurnActive()}
              placeholder={state.selectedThreadID ? "Message Bootstrap harness" : "Type a message to start a new thread"}
              rows={3}
              onKeyDown={(event: KeyboardEvent) => {
                if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
                  event.preventDefault()
                  void handleSend()
                }
              }}
            />
            <div class="composer-row">
              <div class="selectors">
                <span class="pill">Model: Default</span>
                <span class="pill">Agent: Default</span>
              </div>
              <div class="composer-actions">
                <button class="ghost" disabled={!isTurnActive()} onClick={handleCancel}>
                  Cancel turn
                </button>
                <button
                  class="action primary"
                  disabled={!composerText().trim() || isTurnActive()}
                  onClick={() => void handleSend()}
                >
                  Send
                </button>
              </div>
            </div>
          </footer>
        </Show>
      </main>
    </div>
  )
}

function PlaceholderView(props: { mode: "automations" | "skills" | "thread" }) {
  const title = props.mode === "automations" ? "Automations" : "Skills"
  return (
    <section class="placeholder-view">
      <h2>{title}</h2>
      <p>Layout-complete placeholder for MVP. Management flows are coming in a later phase.</p>
    </section>
  )
}

function TimelineCard(props: {
  item: TimelineItem
  onApproval: (requestId: string, decision: "once" | "always" | "reject") => void
}) {
  const tone =
    props.item.type === "user_message"
      ? "tone-user"
      : props.item.type === "assistant_message"
        ? "tone-assistant"
        : props.item.type === "tool_exec" || props.item.type === "tool_log"
          ? "tone-tool"
          : props.item.type === "approval"
            ? "tone-approval"
            : "tone-default"

  const heading =
    props.item.type === "user_message"
      ? "You"
      : props.item.type === "assistant_message"
        ? "Assistant"
        : props.item.type === "tool_exec" || props.item.type === "tool_log"
          ? "Tool"
          : props.item.type === "approval"
            ? "Approval"
            : props.item.type.replaceAll("_", " ")

  const requestId =
    typeof props.item.data?.requestId === "string"
      ? props.item.data.requestId
      : typeof props.item.data?.requestID === "string"
        ? props.item.data.requestID
        : undefined

  return (
    <article class={`timeline-card ${tone} ${props.item.type}`}>
      <header>
        <span>{heading}</span>
        <span>{props.item.completed ? "done" : "live"}</span>
      </header>
      <pre>{props.item.content || JSON.stringify(props.item.data ?? {}, null, 2)}</pre>
      <Show when={props.item.type === "approval" && requestId && !props.item.completed}>
        <div class="approval-actions">
          <button class="ghost" onClick={() => requestId && props.onApproval(requestId, "once")}>
            Allow once
          </button>
          <button class="ghost" onClick={() => requestId && props.onApproval(requestId, "always")}>
            Always allow
          </button>
          <button class="ghost" onClick={() => requestId && props.onApproval(requestId, "reject")}>
            Reject
          </button>
        </div>
      </Show>
    </article>
  )
}
