import { describe, expect, it } from "bun:test"
import { createAppStore, createInitialState, eventKey, reduceEvent } from "../../src/electron/renderer/store"

describe("renderer event reducer", () => {
  it("builds stable event keys with defaults", () => {
    const key = eventKey({ method: "foo", params: {} })
    expect(key).toBe("foo|-|-|-|-")
  })

  it("includes nested item identifiers in event keys", () => {
    const key = eventKey({
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-1",
          threadId: "thread-1",
          turnId: "turn-1",
        },
      },
    })
    expect(key).toBe("item.started|thread-1|turn-1|item-1|-")
  })

  it("handles item started, delta, completed", () => {
    let state = createInitialState("/tmp/work")

    state = reduceEvent(state, {
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-1",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
        },
      },
    })

    state = reduceEvent(state, {
      method: "item.delta",
      params: {
        notification: "item.delta",
        itemId: "item-1",
        threadId: "thread-1",
        turnId: "turn-1",
        delta: "hello",
      },
    })

    state = reduceEvent(state, {
      method: "item.completed",
      params: {
        notification: "item.completed",
        itemId: "item-1",
        threadId: "thread-1",
        turnId: "turn-1",
      },
    })

    const timeline = state.timelines["thread-1"]
    expect(timeline).toHaveLength(1)
    expect(timeline[0].content).toBe("hello")
    expect(timeline[0].completed).toBeTrue()
  })

  it("does not dedupe repeated item.delta events", () => {
    let state = createInitialState("/tmp/work")

    state = reduceEvent(state, {
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-1",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
        },
      },
    })

    state = reduceEvent(state, {
      method: "item.delta",
      params: {
        notification: "item.delta",
        itemId: "item-1",
        threadId: "thread-1",
        turnId: "turn-1",
        delta: "a",
      },
    })

    state = reduceEvent(state, {
      method: "item.delta",
      params: {
        notification: "item.delta",
        itemId: "item-1",
        threadId: "thread-1",
        turnId: "turn-1",
        delta: "a",
      },
    })

    const timeline = state.timelines["thread-1"]
    expect(timeline).toHaveLength(1)
    expect(timeline[0].content).toBe("aa")
  })

  it("does not collapse distinct item.started events", () => {
    let state = createInitialState("/tmp/work")

    state = reduceEvent(state, {
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-1",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
        },
      },
    })

    state = reduceEvent(state, {
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-2",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
        },
      },
    })

    expect(state.timelines["thread-1"]).toHaveLength(2)
    expect(state.timelines["thread-1"][0].itemId).toBe("item-1")
    expect(state.timelines["thread-1"][1].itemId).toBe("item-2")
  })

  it("handles thread created and ignores duplicate or invalid thread ids", () => {
    const initial = createInitialState("/tmp/work")
    const created = reduceEvent(initial, {
      method: "thread.created",
      params: {
        notification: "thread.created",
        threadId: "thread-1",
        title: "First",
      },
    })
    expect(created.threads).toHaveLength(1)
    expect(created.threads[0].title).toBe("First")

    const duplicate = reduceEvent(created, {
      method: "thread.created",
      params: {
        notification: "thread.created",
        threadId: "thread-1",
        title: "Duplicate",
        timestamp: 1,
      },
    })
    expect(duplicate.threads).toHaveLength(1)

    const invalid = reduceEvent(created, {
      method: "thread.created",
      params: {
        notification: "thread.created",
        title: "Missing id",
        timestamp: 2,
      },
    })
    expect(invalid.threads).toHaveLength(1)
  })

  it("replaces optimistic items during item.started", () => {
    const state = createInitialState("/tmp/work")
    state.timelines["thread-1"] = [
      {
        itemId: "optimistic:1",
        threadId: "thread-1",
        turnId: "turn-1",
        type: "assistant_message",
        content: "",
        started: true,
        completed: false,
        interrupted: false,
      },
    ]

    const next = reduceEvent(state, {
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-actual",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
          data: { a: 1 },
        },
      },
    })

    expect(next.timelines["thread-1"]).toHaveLength(1)
    expect(next.timelines["thread-1"][0].itemId).toBe("item-actual")
    expect(next.timelines["thread-1"][0].data).toEqual({ a: 1 })
  })

  it("handles turn lifecycle and approvals and crash banner", () => {
    let state = createInitialState("/tmp/work")

    state = reduceEvent(state, {
      method: "turn.started",
      params: {
        notification: "turn.started",
        threadId: "thread-1",
        turnId: "turn-1",
      },
    })
    expect(state.activeTurns["thread-1"].turnId).toBe("turn-1")

    state = reduceEvent(state, {
      method: "approval.requested",
      params: {
        notification: "approval.requested",
        requestId: "req-1",
        itemId: "item-1",
        threadId: "thread-1",
        turnId: "turn-1",
      },
    })
    expect(state.pendingApprovals["req-1"].itemId).toBe("item-1")

    state = reduceEvent(state, {
      method: "turn.completed",
      params: {
        notification: "turn.completed",
        threadId: "thread-1",
      },
    })
    expect(state.activeTurns["thread-1"]).toBeUndefined()

    state = reduceEvent(state, {
      method: "harness.crash",
      params: { notification: "harness.crash", timestamp: 999 },
    })
    expect(state.status).toBe("error")
    expect(state.banner).toContain("Harness process stopped")
  })

  it("returns next state for unknown notifications", () => {
    const initial = createInitialState("/tmp/work")
    const next = reduceEvent(initial, { method: "unknown", params: { hello: "world" } })
    expect(next).not.toBe(initial)
    expect(next.workspace).toBe(initial.workspace)
  })

  it("is idempotent for duplicate notifications", () => {
    const initial = createInitialState("/tmp/work")
    const event = {
      method: "turn.started",
      params: {
        notification: "turn.started",
        threadId: "thread-1",
        turnId: "turn-1",
        timestamp: 100,
      },
    }

    const first = reduceEvent(initial, event)
    const second = reduceEvent(first, event)
    expect(Object.keys(second.activeTurns)).toHaveLength(1)
    expect(second.activeTurns["thread-1"].turnId).toBe("turn-1")
  })

  it("exercises app store API methods", () => {
    const { state, api } = createAppStore("/tmp/work")

    api.setWorkspace("/tmp/new")
    api.setStatus("loading")
    api.setBanner("b")
    api.setWarning("w")
    api.setThreads([
      {
        threadId: "thread-1",
        title: "Thread",
        directory: "/tmp/new",
        time: { created: 1, updated: 2 },
      },
    ])
    api.setSelectedThread("thread-1")
    api.setView("skills")
    api.setTimeline("thread-1", [])
    api.addOptimisticUserItem("thread-1", "hello")
    api.markOptimisticTurn("thread-1", "turn-1")
    api.apply({
      method: "item.started",
      params: {
        notification: "item.started",
        item: {
          itemId: "item-1",
          threadId: "thread-1",
          turnId: "turn-1",
          type: "assistant_message",
        },
      },
    })
    api.hardReset("/tmp/reset")

    expect(state.workspace).toBe("/tmp/reset")
    expect(state.view).toBe("thread")
    expect(state.threads).toHaveLength(0)
  })
})
