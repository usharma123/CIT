import { describe, expect, it } from "bun:test"
import fs from "node:fs/promises"
import path from "node:path"
import { Global } from "../../src/global"
import { Log } from "../../src/util/log"

describe("log utility", () => {
  it("creates and uses logger before initialization", () => {
    const logger = Log.create({ service: `default-${Date.now()}` })
    logger.info("before init")
    expect(typeof logger.info).toBe("function")
  })

  it("supports levels, formatting, caching, clone, tag and timers", async () => {
    await Log.init({ print: true, level: "DEBUG" })

    const service = `svc-${Date.now()}`
    const first = Log.create({ service })
    const second = Log.create({ service })
    expect(first).toBe(second)

    const tagged = first.tag("scope", "test")
    expect(tagged).toBe(first)
    expect(first.clone()).toBe(first)

    first.debug("debug")
    first.info("info")
    first.warn("warn")
    first.error("error", { err: new Error("outer", { cause: new Error("inner") }) })

    const timer = first.time("timed", { x: 1 })
    timer.stop()
    timer[Symbol.dispose]()
    expect(typeof timer.stop).toBe("function")
  })

  it("writes to file and cleans stale logs", async () => {
    await fs.mkdir(Global.Path.log, { recursive: true })

    const staleLogs = Array.from({ length: 12 }, (_, index) => {
      const suffix = String(index).padStart(2, "0")
      return path.join(Global.Path.log, `2024-01-01T0000${suffix}.log`)
    })
    await Promise.all(staleLogs.map((file) => fs.writeFile(file, "old")))

    await Log.init({ print: false, dev: true, level: "WARN" })
    const logger = Log.create({ service: `file-${Date.now()}` })
    logger.debug("skip")
    logger.info("skip")
    logger.warn("saved")
    logger.error("saved")

    await Bun.sleep(50)

    const logfile = Log.file()
    const text = await Bun.file(logfile).text()
    expect(logfile.endsWith("dev.log")).toBeTrue()
    expect(text.includes("WARN")).toBeTrue()
    expect(text.includes("ERROR")).toBeTrue()

    const remaining = await fs.readdir(Global.Path.log)
    expect(remaining.length).toBeLessThan(13)
  })
})
