import type { HarnessBridge } from "./harness-bridge"
import type { HarnessMethod, HarnessRequestMap } from "./types"

type WindowLike = {
  webContents: {
    send(channel: string, payload: unknown): void
  }
  minimize(): void
  maximize(): void
  unmaximize(): void
  isMaximized(): boolean
  close(): void
}

type IPCMainLike = {
  handle(channel: string, handler: (_event: unknown, ...args: any[]) => unknown): void
}

export function setupBridgeForwarding(bridge: HarnessBridge, getWindow: () => WindowLike | undefined) {
  return bridge.subscribe((event) => {
    getWindow()?.webContents.send("harness:event", event)
  })
}

export function setupIPCHandlers(
  ipcMain: IPCMainLike,
  bridge: HarnessBridge,
  getWindow: () => WindowLike | undefined,
) {
  ipcMain.handle("harness:request", async (_event, payload: { method: HarnessMethod; params?: unknown }) => {
    if (payload.method === "setWorkspace") {
      const input = (payload.params ?? {}) as HarnessRequestMap["setWorkspace"]["params"]
      if (!input.directory || typeof input.directory !== "string") {
        throw new Error("setWorkspace requires directory")
      }
      await bridge.setWorkspace(input.directory)
      return { ok: true, directory: input.directory }
    }

    if (payload.method === "reconnect") {
      await bridge.reconnect()
      return { ok: true }
    }

    return await bridge.request(payload.method, payload.params as never)
  })

  ipcMain.handle("app:window", (_event, action: "minimize" | "maximize" | "close") => {
    const mainWindow = getWindow()
    if (!mainWindow) return { ok: false }

    if (action === "minimize") mainWindow.minimize()
    if (action === "maximize") {
      if (mainWindow.isMaximized()) mainWindow.unmaximize()
      else mainWindow.maximize()
    }
    if (action === "close") mainWindow.close()
    return { ok: true }
  })
}
