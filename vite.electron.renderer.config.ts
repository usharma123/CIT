import path from "node:path"
import { defineConfig } from "vite"
import solid from "vite-plugin-solid"

export default defineConfig({
  root: path.resolve(process.cwd(), "src/electron/renderer"),
  base: "./",
  plugins: [solid()],
  build: {
    outDir: path.resolve(process.cwd(), "dist/renderer"),
    emptyOutDir: true,
  },
})
