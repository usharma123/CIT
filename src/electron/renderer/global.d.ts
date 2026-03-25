import type { BootstrapHarnessAPI } from "../types"

declare global {
  interface Window {
    bootstrapHarness: BootstrapHarnessAPI
  }
}

export {}
