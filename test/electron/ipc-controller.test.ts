import { describe, expect, it } from "bun:test"
import { setupBridgeForwarding, setupIPCHandlers } from "../../src/electron/ipc-controller"

function createIPCMock() {
  const handlers = new Map<string, (_event: unknown, ...args: any[]) => unknown>()
  return {
    handle(channel: string, handler: (_event: unknown, ...args: any[]) => unknown) {
      handlers.set(channel, handler)
    },
    async invoke(channel: string, ...args: any[]) {
      const handler = handlers.get(channel)
      if (!handler) throw new Error(`Missing handler: ${channel}`)
      return await handler({}, ...args)
    },
  }
}

function createBridgeMock() {
  const subscriptions = new Set<(event: any) => void>()
  return {
    requests: [] as any[],
    workspace: [] as string[],
    reconnects: 0,
    subscribe(handler: (event: any) => void) {
      subscriptions.add(handler)
      return () => subscriptions.delete(handler)
    },
    emit(event: any) {
      for (const handler of subscriptions) handler(event)
    },
    async request(method: string, params?: unknown) {
      this.requests.push({ method, params })
      return { ok: true, method, params }
    },
    async setWorkspace(directory: string) {
      this.workspace.push(directory)
    },
    async reconnect() {
      this.reconnects += 1
    },
  }
}

function createWindowMock() {
  const sent: Array<{ channel: string; payload: unknown }> = []
  return {
    sent,
    minimized: 0,
    maximized: false,
    closed: 0,
    webContents: {
      send(channel: string, payload: unknown) {
        sent.push({ channel, payload })
      },
    },
    minimize() {
      this.minimized += 1
    },
    maximize() {
      this.maximized = true
    },
    unmaximize() {
      this.maximized = false
    },
    isMaximized() {
      return this.maximized
    },
    close() {
      this.closed += 1
    },
  }
}

describe("ipc controller", () => {
  it("forwards harness events to renderer channel", () => {
    const bridge = createBridgeMock()
    const windowMock = createWindowMock()

    const unsubscribe = setupBridgeForwarding(bridge as any, () => windowMock as any)
    bridge.emit({ method: "item.delta", params: { value: 1 } })
    unsubscribe()

    expect(windowMock.sent).toHaveLength(1)
    expect(windowMock.sent[0].channel).toBe("harness:event")
  })

  it("routes harness requests including workspace and reconnect", async () => {
    const ipc = createIPCMock()
    const bridge = createBridgeMock()
    const windowMock = createWindowMock()

    setupIPCHandlers(ipc as any, bridge as any, () => windowMock as any)

    const listResult = await ipc.invoke("harness:request", { method: "threadList", params: {} })
    expect(listResult).toEqual({ ok: true, method: "threadList", params: {} })

    const setWorkspaceResult = await ipc.invoke("harness:request", {
      method: "setWorkspace",
      params: { directory: "/tmp/workspace" },
    })
    expect(setWorkspaceResult).toEqual({ ok: true, directory: "/tmp/workspace" })
    expect(bridge.workspace).toEqual(["/tmp/workspace"])

    const reconnectResult = await ipc.invoke("harness:request", { method: "reconnect", params: {} })
    expect(reconnectResult).toEqual({ ok: true })
    expect(bridge.reconnects).toBe(1)
  })

  it("validates workspace payload and handles window actions", async () => {
    const ipc = createIPCMock()
    const bridge = createBridgeMock()
    const windowMock = createWindowMock()

    setupIPCHandlers(ipc as any, bridge as any, () => windowMock as any)

    await expect(
      ipc.invoke("harness:request", {
        method: "setWorkspace",
        params: {},
      }),
    ).rejects.toThrow("setWorkspace requires directory")

    expect(await ipc.invoke("app:window", "minimize")).toEqual({ ok: true })
    expect(windowMock.minimized).toBe(1)

    expect(await ipc.invoke("app:window", "maximize")).toEqual({ ok: true })
    expect(windowMock.maximized).toBeTrue()

    expect(await ipc.invoke("app:window", "maximize")).toEqual({ ok: true })
    expect(windowMock.maximized).toBeFalse()

    expect(await ipc.invoke("app:window", "close")).toEqual({ ok: true })
    expect(windowMock.closed).toBe(1)
  })

  it("supports crash event forwarding and reconnect flow", async () => {
    const ipc = createIPCMock()
    const bridge = createBridgeMock()
    const windowMock = createWindowMock()

    setupBridgeForwarding(bridge as any, () => windowMock as any)
    setupIPCHandlers(ipc as any, bridge as any, () => windowMock as any)

    bridge.emit({ method: "harness.crash", params: { code: 1 } })
    expect(windowMock.sent[0]).toEqual({
      channel: "harness:event",
      payload: { method: "harness.crash", params: { code: 1 } },
    })

    const reconnectResult = await ipc.invoke("harness:request", {
      method: "reconnect",
      params: {},
    })
    expect(reconnectResult).toEqual({ ok: true })
    expect(bridge.reconnects).toBe(1)
  })
})
