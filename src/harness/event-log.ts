import fs from "fs/promises"
import path from "path"
import { Log } from "@/util/log"

export namespace EventLog {
  const log = Log.create({ service: "harness-event-log" })

  function eventsDir(directory: string, threadId: string) {
    return path.join(directory, ".harness", "threads", threadId)
  }

  function eventsPath(directory: string, threadId: string) {
    return path.join(eventsDir(directory, threadId), "events.jsonl")
  }

  function metaPath(directory: string, threadId: string) {
    return path.join(eventsDir(directory, threadId), "meta.json")
  }

  export async function append(directory: string, threadId: string, event: Record<string, unknown>) {
    const dir = eventsDir(directory, threadId)
    await fs.mkdir(dir, { recursive: true })
    const filePath = eventsPath(directory, threadId)
    const line = JSON.stringify({ ...event, timestamp: Date.now() }) + "\n"
    await fs.appendFile(filePath, line, "utf-8")
  }

  export async function replay(directory: string, threadId: string): Promise<Record<string, unknown>[]> {
    const filePath = eventsPath(directory, threadId)
    try {
      const content = await fs.readFile(filePath, "utf-8")
      const events: Record<string, unknown>[] = []
      for (const line of content.split("\n")) {
        if (!line.trim()) continue
        try {
          events.push(JSON.parse(line))
        } catch {
          log.warn("skipping malformed event log line", { threadId })
        }
      }
      return events
    } catch (e: any) {
      if (e.code === "ENOENT") return []
      throw e
    }
  }

  export async function writeMeta(directory: string, threadId: string, meta: Record<string, unknown>) {
    const dir = eventsDir(directory, threadId)
    await fs.mkdir(dir, { recursive: true })
    await fs.writeFile(metaPath(directory, threadId), JSON.stringify(meta, null, 2), "utf-8")
  }

  export async function readMeta(directory: string, threadId: string): Promise<Record<string, unknown> | undefined> {
    try {
      const content = await fs.readFile(metaPath(directory, threadId), "utf-8")
      return JSON.parse(content)
    } catch {
      return undefined
    }
  }
}
