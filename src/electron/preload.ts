import { contextBridge, ipcRenderer } from "electron"
import type { BootstrapHarnessAPI, HarnessEvent, StartTurnInput, ApprovalInput } from "./types"

const api: BootstrapHarnessAPI = {
  async initialize() {
    return await ipcRenderer.invoke("harness:request", { method: "initialize", params: {} })
  },

  async setWorkspace(directory: string) {
    await ipcRenderer.invoke("harness:request", { method: "setWorkspace", params: { directory } })
  },

  async reconnect() {
    await ipcRenderer.invoke("harness:request", { method: "reconnect", params: {} })
  },

  async listThreads() {
    const result = await ipcRenderer.invoke("harness:request", { method: "threadList", params: {} })
    return result.threads
  },

  async createThread(input: { title?: string; directory?: string }) {
    const result = await ipcRenderer.invoke("harness:request", { method: "threadCreate", params: input })
    return result.thread
  },

  async getThread(threadId: string) {
    return await ipcRenderer.invoke("harness:request", { method: "threadGet", params: { threadId } })
  },

  async startTurn(input: StartTurnInput) {
    return await ipcRenderer.invoke("harness:request", {
      method: "turnStart",
      params: {
        threadId: input.threadId,
        input: [{ type: "text", text: input.text }],
        model: input.model,
        agent: input.agent,
      },
    })
  },

  async cancelTurn(threadId: string) {
    return await ipcRenderer.invoke("harness:request", { method: "turnCancel", params: { threadId } })
  },

  async respondApproval(input: ApprovalInput) {
    return await ipcRenderer.invoke("harness:request", { method: "approvalRespond", params: input })
  },

  subscribe(handler: (event: HarnessEvent) => void) {
    const listener = (_event: Electron.IpcRendererEvent, payload: HarnessEvent) => {
      handler(payload)
    }

    ipcRenderer.on("harness:event", listener)
    return () => ipcRenderer.off("harness:event", listener)
  },
}

contextBridge.exposeInMainWorld("bootstrapHarness", api)
