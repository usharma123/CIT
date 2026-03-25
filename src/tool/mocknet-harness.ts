import { createHash } from "crypto"
import * as fs from "fs"
import * as path from "path"
import z from "zod"
import { Tool } from "./tool"
import { Instance } from "../project/instance"
import { Filesystem } from "../util/filesystem"

const HARNESS_REL = path.join("presentation", "bootstrap-mocknet-integration")

/** Exported for unit tests — parses ## / ### headings into requirement chunks. */
export function parseSpecMarkdown(content: string): {
  requirements: { id: string; title: string; summary: string }[]
} {
  const lines = content.split(/\r?\n/)
  const requirements: { id: string; title: string; summary: string }[] = []
  let current: { id: string; title: string; summary: string } | null = null

  const pushCurrent = () => {
    if (current) {
      current.summary = current.summary.trim()
      requirements.push(current)
      current = null
    }
  }

  for (const line of lines) {
    const m = line.match(/^#{2,3}\s+(.+)/)
    if (m) {
      pushCurrent()
      const title = m[1].trim()
      const id = title
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "")
      current = { id: id || "section", title, summary: "" }
    } else if (current && !line.startsWith("#")) {
      current.summary += `${line}\n`
    }
  }
  pushCurrent()

  if (requirements.length === 0) {
    return {
      requirements: [
        {
          id: "whole-document",
          title: "Document",
          summary: content.slice(0, 12_000).trim(),
        },
      ],
    }
  }
  return { requirements }
}

const defaultInputModel = () => ({
  messageId: "MSG-PLAN-001",
  tradeId: "TRD-PLAN-001",
  tradeType: "SPOT",
  party1Id: "BANK_A",
  party1Role: "BUYER",
  party2Id: "BANK_B",
  party2Role: "SELLER",
  currency1: "USD",
  amount1: "100000.00",
  currency2: "GBP",
  amount2: "79000.00",
  exchangeRate: "1.2658228",
  valueDate: "2026-04-01",
  creationTimestamp: "2026-03-24T10:00:00Z",
})

/** Build a v1 scenario manifest skeleton from parsed requirements (deterministic). */
export function buildManifestFromRequirements(
  requirements: { id: string; title: string; summary: string }[],
  specId: string,
) {
  return {
    manifestVersion: "1" as const,
    specId,
    scenarios: requirements.map((r, i) => ({
      scenarioId: `${r.id || "req"}-${i}`,
      requirementRef: r.title,
      intent: r.summary.slice(0, 400) || r.title,
      inputModel: { ...defaultInputModel(), messageId: `MSG-PLAN-${i}`, tradeId: `TRD-PLAN-${i}` },
      mutations: [] as string[],
      expected: { httpStatus: 202, finalTradeStatus: "VALIDATED" as const },
    })),
  }
}

/** Lightweight contract summary from FpmlTradeMessage.java source (deterministic regex). */
export function summarizeFpmlContract(javaSource: string): {
  rootElement: string | null
  nestedTypes: string[]
  contractHash: string
} {
  const contractHash = createHash("sha256").update(javaSource).digest("hex")
  const root = javaSource.match(/@JacksonXmlRootElement\(localName\s*=\s*"([^"]+)"/)
  const nested = new Set<string>()
  for (const m of javaSource.matchAll(/public static class (\w+)/g)) {
    nested.add(m[1])
  }
  return {
    rootElement: root?.[1] ?? null,
    nestedTypes: [...nested],
    contractHash,
  }
}

function resolveProjectPath(filePath: string): string {
  if (path.isAbsolute(filePath)) return filePath
  return path.join(Instance.directory, filePath)
}

function harnessDir(): string {
  return path.join(Instance.directory, HARNESS_REL)
}

function pythonForHarness(harnessRoot: string): string {
  const win = path.join(harnessRoot, ".venv", "Scripts", "python.exe")
  const posix = path.join(harnessRoot, ".venv", "bin", "python3")
  if (process.platform === "win32" && fs.existsSync(win)) return win
  if (fs.existsSync(posix)) return posix
  return "python3"
}

export const SpecIngestTool = Tool.define("spec_ingest", {
  description:
    "Parse a Markdown spec file into normalized requirement objects (id, title, summary). Use before scenario_plan. Does not write files.",
  parameters: z.object({
    filePath: z
      .string()
      .describe("Path to the Markdown spec, relative to the project root unless absolute"),
  }),
  async execute(params, ctx) {
    const filepath = resolveProjectPath(params.filePath)
    if (!Filesystem.contains(Instance.directory, filepath)) {
      await ctx.ask({
        permission: "external_directory",
        patterns: [path.dirname(filepath)],
        always: [path.dirname(filepath) + "/*"],
        metadata: { filepath },
      })
    }
    await ctx.ask({
      permission: "read",
      patterns: [filepath],
      always: ["*"],
      metadata: {},
    })
    const content = fs.readFileSync(filepath, "utf-8")
    const specHash = createHash("sha256").update(content).digest("hex")
    const parsed = parseSpecMarkdown(content)
    return {
      title: `${parsed.requirements.length} requirements`,
      output: JSON.stringify({ specHash, ...parsed }, null, 2),
      metadata: { specHash, requirementCount: parsed.requirements.length },
    }
  },
})

export const ScenarioPlanTool = Tool.define("scenario_plan", {
  description:
    "Expand parsed requirements into a v1 scenario manifest JSON (deterministic skeleton). Pass output of spec_ingest or a spec file path.",
  parameters: z.object({
    specId: z.string().describe("Logical spec id for the manifest"),
    requirementsJson: z
      .string()
      .describe('JSON string: { "requirements": [ { "id", "title", "summary" } ] }'),
  }),
  async execute(params, ctx) {
    await ctx.ask({
      permission: "read",
      patterns: ["*"],
      always: ["*"],
      metadata: {},
    })
    const body = JSON.parse(params.requirementsJson) as {
      requirements?: { id: string; title: string; summary: string }[]
    }
    if (!body.requirements?.length) {
      throw new Error('requirementsJson must contain a non-empty "requirements" array')
    }
    const specHash = createHash("sha256").update(params.requirementsJson).digest("hex")
    const manifest = buildManifestFromRequirements(body.requirements, params.specId)
    return {
      title: `${manifest.scenarios.length} scenarios`,
      output: JSON.stringify({ specHash, manifest }, null, 2),
      metadata: { specHash, scenarioCount: manifest.scenarios.length },
    }
  },
})

export const MocknetContractTool = Tool.define("mocknet_contract", {
  description:
    "Summarize the mocknet FpML XML contract from FpmlTradeMessage.java (root element, nested types, sha256). Use for cache keys with spec_hash.",
  parameters: z.object({
    javaPath: z
      .string()
      .optional()
      .describe(
        "Path to FpmlTradeMessage.java (default: mocknet/src/main/java/com/cit/clsnet/xml/FpmlTradeMessage.java)",
      ),
  }),
  async execute(params, ctx) {
    const rel =
      params.javaPath ?? path.join("mocknet", "src", "main", "java", "com", "cit", "clsnet", "xml", "FpmlTradeMessage.java")
    const filepath = resolveProjectPath(rel)
    await ctx.ask({
      permission: "read",
      patterns: [filepath],
      always: ["*"],
      metadata: {},
    })
    const src = fs.readFileSync(filepath, "utf-8")
    const summary = summarizeFpmlContract(src)
    return {
      title: `contract ${summary.rootElement ?? "?"}`,
      output: JSON.stringify(summary, null, 2),
      metadata: { contractHash: summary.contractHash },
    }
  },
})

export const MocknetRunTool = Tool.define("mocknet_run", {
  description:
    "Run presentation/bootstrap-mocknet-integration phase2 Python harness against a manifest (JSON). Uses project Python; pass dryRun to validate without network.",
  parameters: z.object({
    manifestPath: z
      .string()
      .describe(
        "Manifest path relative to presentation/bootstrap-mocknet-integration (e.g. examples/sample-scenarios.json)",
      ),
    baseUrl: z.string().optional().describe("Mocknet base URL (default http://localhost:8080)"),
    dryRun: z.boolean().optional().describe("If true, passes --dry-run to Python runner"),
    timeoutMs: z.number().optional().describe("Subprocess max time in ms (default 120000)"),
  }),
  async execute(params, ctx) {
    const dir = harnessDir()
    const script = path.join(dir, "phase2", "mocknet_run.py")
    const manifestAbs = path.join(dir, params.manifestPath)
    if (!fs.existsSync(dir) || !fs.existsSync(script)) {
      throw new Error(`Mocknet harness not found at ${dir}. Expected ${HARNESS_REL} in this repo.`)
    }
    if (!fs.existsSync(manifestAbs)) {
      throw new Error(`Manifest not found: ${manifestAbs}`)
    }
    if (!Filesystem.contains(Instance.directory, manifestAbs)) {
      await ctx.ask({
        permission: "external_directory",
        patterns: [path.dirname(manifestAbs)],
        always: [path.dirname(manifestAbs) + "/*"],
        metadata: { manifestAbs },
      })
    }
    await ctx.ask({
      permission: "bash",
      patterns: [script, manifestAbs],
      always: ["*"],
      metadata: {
        description: "Run mocknet scenario harness (python3)",
        command: "python3 phase2/mocknet_run.py ...",
      },
    })

    const baseUrl = params.baseUrl ?? "http://localhost:8080"
    const timeout = params.timeoutMs ?? 120_000
    const py = pythonForHarness(dir)
    const args = [py, script, manifestAbs, "--base-url", baseUrl]
    if (params.dryRun) args.push("--dry-run")

    const proc = Bun.spawn(args, {
      cwd: dir,
      stdout: "pipe",
      stderr: "pipe",
    })
    const killTimer = setTimeout(() => {
      proc.kill()
    }, timeout)
    const exit = await proc.exited
    clearTimeout(killTimer)
    const out = await new Response(proc.stdout).text()
    const err = await new Response(proc.stderr).text()
    const combined = err ? `${out}\n${err}` : out
    if (exit !== 0) {
      throw new Error(`mocknet_run failed (exit ${exit}):\n${combined.slice(0, 8000)}`)
    }
    return {
      title: "mocknet_run complete",
      output: combined.slice(0, 30_000),
      metadata: { exitCode: exit },
    }
  },
})
