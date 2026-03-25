import { describe, expect, test } from "bun:test"
import path from "path"
import {
  MocknetContractTool,
  MocknetRunTool,
  ScenarioPlanTool,
  SpecIngestTool,
  buildManifestFromRequirements,
  parseSpecMarkdown,
  summarizeFpmlContract,
} from "../../src/tool/mocknet-harness"
import { Instance } from "../../src/project/instance"
import { tmpdir } from "../fixture/fixture"

const ctx = {
  sessionID: "test",
  messageID: "",
  callID: "",
  agent: "build",
  abort: AbortSignal.any([]),
  metadata: () => {},
  ask: async () => {},
}

describe("mocknet-harness pure helpers", () => {
  test("parseSpecMarkdown extracts headings", () => {
    const md = `## Netting rules\nMust pair trades.\n\n## Cutoff\n5pm.\n`
    const { requirements } = parseSpecMarkdown(md)
    expect(requirements.length).toBe(2)
    expect(requirements[0].title).toBe("Netting rules")
    expect(requirements[0].summary).toContain("Must pair")
  })

  test("buildManifestFromRequirements is deterministic shape", () => {
    const m = buildManifestFromRequirements(
      [{ id: "a", title: "T1", summary: "S1" }],
      "spec-x",
    )
    expect(m.manifestVersion).toBe("1")
    expect(m.specId).toBe("spec-x")
    expect(m.scenarios[0].scenarioId).toBe("a-0")
    expect(m.scenarios[0].expected.finalTradeStatus).toBe("VALIDATED")
  })

  test("summarizeFpmlContract finds root element", () => {
    const src = `
@JacksonXmlRootElement(localName = "tradeMessage")
public class FpmlTradeMessage {
  public static class Header { }
}`
    const s = summarizeFpmlContract(src)
    expect(s.rootElement).toBe("tradeMessage")
    expect(s.nestedTypes).toContain("Header")
    expect(s.contractHash.length).toBe(64)
  })
})

describe("mocknet-harness tools", () => {
  test("spec_ingest reads markdown in project", async () => {
    await using tmp = await tmpdir({
      init: async (dir) => {
        await Bun.write(path.join(dir, "spec.md"), "## Req A\nBody.\n")
      },
    })
    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const t = await SpecIngestTool.init()
        const r = await t.execute({ filePath: path.join(tmp.path, "spec.md") }, ctx)
        expect(r.output).toContain("Req A")
        expect(r.output).toContain("specHash")
      },
    })
  })

  test("scenario_plan builds manifest JSON", async () => {
    const repoRoot = new URL("../..", import.meta.url).pathname
    await Instance.provide({
      directory: repoRoot,
      fn: async () => {
        const t = await ScenarioPlanTool.init()
        const reqs = JSON.stringify({
          requirements: [{ id: "x", title: "T", summary: "S" }],
        })
        const r = await t.execute({ specId: "s1", requirementsJson: reqs }, ctx)
        expect(r.output).toContain("manifestVersion")
        expect(r.output).toContain("TRD-PLAN-0")
      },
    })
  })

  test("mocknet_contract reads default FpmlTradeMessage", async () => {
    const repoRoot = new URL("../..", import.meta.url).pathname
    const fpml = path.join(repoRoot, "mocknet/src/main/java/com/cit/clsnet/xml/FpmlTradeMessage.java")
    if (!(await Bun.file(fpml).exists())) {
      console.warn("skip mocknet_contract: FpmlTradeMessage.java not found")
      return
    }
    await Instance.provide({
      directory: repoRoot,
      fn: async () => {
        const t = await MocknetContractTool.init()
        const r = await t.execute({}, ctx)
        expect(r.output).toContain("tradeMessage")
        expect(r.output).toContain("contractHash")
      },
    })
  })

  test("mocknet_run dry-run against sample manifest", async () => {
    const repoRoot = new URL("../..", import.meta.url).pathname
    const harness = path.join(repoRoot, "presentation/bootstrap-mocknet-integration")
    const sample = path.join(harness, "examples/sample-scenarios.json")
    if (!(await Bun.file(sample).exists())) {
      console.warn("skip mocknet_run: sample manifest missing")
      return
    }
    await Instance.provide({
      directory: repoRoot,
      fn: async () => {
        const t = await MocknetRunTool.init()
        const r = await t.execute(
          { manifestPath: "examples/sample-scenarios.json", dryRun: true },
          ctx,
        )
        expect(r.output).toContain("allPassed")
      },
    })
  })
})
