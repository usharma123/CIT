import { Log } from "@/util/log"
import { bootstrap } from "../bootstrap"
import { cmd } from "./cmd"
import { Server } from "@/server/server"
import { HarnessServer } from "@/harness/server"
import { withNetworkOptions, resolveNetworkOptions } from "../network"

const log = Log.create({ service: "harness-command" })

export const HarnessCommand = cmd({
  command: "harness",
  describe: "start harness JSON-RPC server over stdio",
  builder: (yargs) => {
    return withNetworkOptions(yargs).option("cwd", {
      describe: "working directory",
      type: "string",
      default: process.cwd(),
    })
  },
  handler: async (args) => {
    const directory = args.cwd as string
    await bootstrap(directory, async () => {
      const opts = await resolveNetworkOptions(args as any)
      // Start the internal HTTP server (needed by subsystems like MCP)
      Server.listen(opts)

      // Create the harness JSON-RPC server over stdio
      const { transport } = HarnessServer.create({ directory })

      log.info("harness server ready", { directory })

      // Keep alive until stdin closes
      process.stdin.resume()
      await new Promise((resolve, reject) => {
        process.stdin.on("end", resolve)
        process.stdin.on("error", reject)
      })

      transport.close()
    })
  },
})
