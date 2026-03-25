import { describe, expect, it } from "bun:test"
import { PassThrough } from "node:stream"
import { JsonRpcClient } from "../../src/electron/jsonrpc-client"

function setup() {
  const stdin = new PassThrough()
  const stdout = new PassThrough()
  const stderr = new PassThrough()
  const written: string[] = []
  stdin.on("data", (chunk) => written.push(chunk.toString()))

  const client = new JsonRpcClient(stdin, stdout, stderr)
  const close = () => {
    client.close()
    stdin.end()
    stdout.end()
    stderr.end()
  }

  return { client, stdout, stderr, written, close }
}

describe("json rpc client", () => {
  it("correlates request and response by id", async () => {
    const { client, stdout, written, close } = setup()
    const pending = client.request("thread.list", {}, 1000)

    expect(written[0]).toContain("\"method\":\"thread.list\"")
    stdout.write('{"jsonrpc":"2.0","id":1,"result":{"threads":[]}}\n')

    await expect(pending).resolves.toEqual({ threads: [] })
    close()
  })

  it("parses partial lines from stdout", async () => {
    const { client, stdout, written, close } = setup()
    const pending = client.request("initialize", {}, 1000)
    const id = JSON.parse(written[0]).id

    stdout.write(`{"jsonrpc":"2.0","id":${id},"result":{"version":"0.1"`)
    stdout.write(',"capabilities":{}}}')
    stdout.write("\n")

    await expect(pending).resolves.toEqual({ version: "0.1", capabilities: {} })
    close()
  })

  it("emits notifications", async () => {
    const { client, stdout, close } = setup()
    let value = ""

    const unsubscribe = client.onNotification((method) => {
      value = method
    })

    stdout.write('{"jsonrpc":"2.0","method":"item.delta","params":{}}\n')
    await Promise.resolve()

    expect(value).toBe("item.delta")
    unsubscribe()
    close()
  })

  it("rejects JSON-RPC error responses", async () => {
    const { client, stdout, written, close } = setup()
    const pending = client.request("turn.start", {}, 1000)
    const id = JSON.parse(written[0]).id

    stdout.write(`{"jsonrpc":"2.0","id":${id},"error":{"code":-32002,"message":"busy"}}\n`)
    await expect(pending).rejects.toThrow("busy")
    close()
  })

  it("times out pending requests", async () => {
    const { client, close } = setup()
    await expect(client.request("thread.get", {}, 10)).rejects.toThrow("Request timed out: thread.get")
    close()
  })

  it("rejects pending requests when closed and refuses new requests", async () => {
    const { client, close } = setup()
    const pending = client.request("thread.list", {}, 1000)
    client.close(new Error("boom"))
    await expect(pending).rejects.toThrow("boom")
    await expect(client.request("thread.list", {})).rejects.toThrow("Harness client is closed")
    close()
  })

  it("ignores invalid lines and unknown ids", async () => {
    const { client, stdout, close } = setup()
    stdout.write("not-json\n")
    stdout.write('{"jsonrpc":"2.0","id":999,"result":{}}\n')
    stdout.write("\n")
    await Promise.resolve()
    close()
  })

  it("closes on stdout end", async () => {
    const { client, stdout, close } = setup()
    const pending = client.request("thread.list", {}, 1000)
    stdout.end()
    await expect(pending).rejects.toThrow("Harness stdout closed")
    close()
  })

  it("handles stderr data and stdout error events", async () => {
    const { client, stdout, stderr, close } = setup()
    const pending = client.request("thread.list", {}, 1000)
    stderr.write("warning\n")
    stdout.emit("error", "stream broke")
    await expect(pending).rejects.toThrow("stream broke")
    client.close()
    close()
  })

  it("rejects pending requests with default close error", async () => {
    const { client, close } = setup()
    const pending = client.request("thread.list", {}, 1000)
    client.close()
    await expect(pending).rejects.toThrow("Harness connection closed")
    close()
  })
})
