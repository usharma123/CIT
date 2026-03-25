#!/usr/bin/env bun

/**
 * Demo CLI client for the harness JSON-RPC server.
 *
 * Usage:
 *   bun src/harness/client.ts --cwd /path/to/repo
 *   bootstrap harness-client --cwd /path/to/repo
 *
 * Spawns `bootstrap harness --cwd <dir>` as a child process,
 * sends JSON-RPC commands, and pretty-prints the streaming timeline.
 */

import { spawn } from "child_process"
import { createInterface } from "readline"
import path from "path"

const args = process.argv.slice(2)
const cwdIndex = args.indexOf("--cwd")
const cwd = cwdIndex !== -1 && args[cwdIndex + 1] ? args[cwdIndex + 1] : process.cwd()

const binPath = path.resolve(import.meta.dir, "../../node_modules/.bin/bootstrap")
const serverProcess = spawn("bun", [path.resolve(import.meta.dir, "../index.ts"), "harness", "--cwd", cwd], {
  stdio: ["pipe", "pipe", "inherit"],
})

const rl = createInterface({ input: serverProcess.stdout! })

let nextId = 1
const pending = new Map<number, { resolve: (result: any) => void; reject: (error: any) => void }>()

rl.on("line", (line) => {
  if (!line.trim()) return
  try {
    const msg = JSON.parse(line)
    if (msg.id !== undefined && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)!
      pending.delete(msg.id)
      if (msg.error) {
        reject(msg.error)
      } else {
        resolve(msg.result)
      }
    } else if (msg.method) {
      // Notification
      printNotification(msg)
    }
  } catch {
    // ignore malformed lines
  }
})

function send(method: string, params?: Record<string, any>): Promise<any> {
  const id = nextId++
  const msg = { jsonrpc: "2.0", id, method, params }
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    serverProcess.stdin!.write(JSON.stringify(msg) + "\n")
  })
}

function printNotification(msg: { method: string; params?: any }) {
  const ts = new Date().toISOString().slice(11, 23)
  const p = msg.params ?? {}

  switch (msg.method) {
    case "turn.started":
      console.log(`[${ts}] >> turn.started  turnId=${p.turnId}`)
      break
    case "turn.completed":
      console.log(`[${ts}] >> turn.completed  turnId=${p.turnId} status=${p.status}`)
      break
    case "turn.error":
      console.log(`[${ts}] >> turn.error  turnId=${p.turnId} error=${p.error}`)
      break
    case "item.started":
      console.log(`[${ts}] >> item.started  type=${p.item?.type} itemId=${p.item?.itemId}`)
      break
    case "item.delta":
      if (p.type === "assistant_message") {
        process.stdout.write(p.delta ?? "")
      } else {
        console.log(`[${ts}] >> item.delta  type=${p.type}`)
      }
      break
    case "item.completed":
      console.log(`\n[${ts}] >> item.completed  type=${p.type} itemId=${p.itemId}`)
      break
    case "approval.requested": {
      console.log(`\n[${ts}] >> approval.requested  permission=${p.permission}`)
      console.log(`   metadata: ${JSON.stringify(p.metadata)}`)
      promptApproval(p.requestId)
      break
    }
    case "thread.created":
      console.log(`[${ts}] >> thread.created  threadId=${p.threadId}`)
      break
    default:
      console.log(`[${ts}] >> ${msg.method}`, JSON.stringify(p).slice(0, 120))
  }
}

async function promptApproval(requestId: string) {
  const termRl = createInterface({ input: process.stdin, output: process.stderr })
  const answer = await new Promise<string>((resolve) => {
    termRl.question("Allow? [y/n/a(lways)] ", resolve)
  })
  termRl.close()

  const decision = answer.trim().toLowerCase().startsWith("a")
    ? "always"
    : answer.trim().toLowerCase().startsWith("y")
      ? "once"
      : "reject"

  await send("approval.respond", { requestId, decision })
}

async function main() {
  console.log(`Harness client starting... cwd=${cwd}`)
  console.log()

  // 1. Initialize
  const initResult = await send("initialize", {})
  console.log(`Initialized: version=${initResult.version}`)
  console.log(`Capabilities: ${JSON.stringify(initResult.capabilities)}`)
  console.log()

  // 2. Create thread
  const threadResult = await send("thread.create", { title: "Demo session" })
  const threadId = threadResult.thread.threadId
  console.log(`Thread created: ${threadId}`)
  console.log()

  // 3. Start turn with user prompt
  const prompt = args.find((a) => !a.startsWith("--") && args.indexOf(a) !== cwdIndex + 1) ?? "Hello! What files are in this directory?"
  console.log(`Sending prompt: "${prompt}"`)
  console.log("---")

  const turnResult = await send("turn.start", {
    threadId,
    input: [{ type: "text", text: prompt }],
  })
  console.log(`Turn started: ${turnResult.turnId}`)
  console.log()

  // Wait for turn to complete (notifications will print via the handler above)
  await new Promise<void>((resolve) => {
    const check = setInterval(() => {
      // The turn completion notification handler could set a flag,
      // but for simplicity we just wait
    }, 100)

    // Listen for turn.completed or turn.error
    const originalHandler = rl.listeners("line")[0] as (line: string) => void
    rl.removeAllListeners("line")
    rl.on("line", (line) => {
      originalHandler(line)
      try {
        const msg = JSON.parse(line)
        if (msg.method === "turn.completed" || msg.method === "turn.error") {
          clearInterval(check)
          setTimeout(() => {
            resolve()
          }, 500)
        }
      } catch {}
    })
  })

  console.log()
  console.log("--- Turn complete ---")

  // 4. Get thread (replay events)
  const getResult = await send("thread.get", { threadId })
  console.log(`\nThread has ${getResult.events.length} events in event log`)

  serverProcess.kill()
  process.exit(0)
}

main().catch((e) => {
  console.error("Client error:", e)
  serverProcess.kill()
  process.exit(1)
})
