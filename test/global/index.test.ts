import { describe, expect, it } from "bun:test"
import fs from "node:fs/promises"
import path from "node:path"
import { Global } from "../../src/global/index"

describe("global paths", () => {
  it("returns override home when BOOTSTRAP_TEST_HOME is set", () => {
    const original = process.env.BOOTSTRAP_TEST_HOME
    process.env.BOOTSTRAP_TEST_HOME = "/tmp/bootstrap-home"
    expect(Global.Path.home).toBe("/tmp/bootstrap-home")
    process.env.BOOTSTRAP_TEST_HOME = original
  })

  it("refresh import cleans cache entries when version differs", async () => {
    await fs.mkdir(Global.Path.cache, { recursive: true })
    await fs.writeFile(path.join(Global.Path.cache, "version"), "0")
    await fs.writeFile(path.join(Global.Path.cache, "old-a"), "a")
    await fs.writeFile(path.join(Global.Path.cache, "old-b"), "b")

    await import(`../../src/global/index.ts?refresh=${Date.now()}`)

    const version = (await Bun.file(path.join(Global.Path.cache, "version")).text()).trim()
    expect(version).toBe("14")

    const entries = await fs.readdir(Global.Path.cache)
    expect(entries).toEqual(["version"])
  })
})
