import path from "node:path"
import { fileURLToPath } from "node:url"
import { app, BrowserWindow, ipcMain } from "electron"
import { HarnessBridge } from "./harness-bridge"
import { setupBridgeForwarding, setupIPCHandlers } from "./ipc-controller"

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

const rendererHTML = path.resolve(__dirname, "../dist/renderer/index.html")
const preloadPath = path.resolve(__dirname, "preload.js")
const devServerURL = process.env.ELECTRON_RENDERER_URL

let mainWindow: BrowserWindow | undefined
const bridge = new HarnessBridge()

async function createWindow() {
  const window = new BrowserWindow({
    width: 1320,
    height: 880,
    minWidth: 900,
    minHeight: 620,
    backgroundColor: "#0d1218",
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  })

  if (devServerURL) {
    await window.loadURL(devServerURL)
  } else {
    await window.loadFile(rendererHTML)
  }

  mainWindow = window
}

app.whenReady().then(async () => {
  setupBridgeForwarding(bridge, () => mainWindow)
  setupIPCHandlers(ipcMain, bridge, () => mainWindow)
  await createWindow()

  app.on("activate", async () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      await createWindow()
    }
  })
})

app.on("window-all-closed", () => {
  bridge.dispose()
  if (process.platform !== "darwin") app.quit()
})
