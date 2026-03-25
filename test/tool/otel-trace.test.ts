import { describe, expect, test } from "bun:test"
import fs from "fs/promises"
import path from "path"
import { Instance } from "../../src/project/instance"
import { OtelTraceTool } from "../../src/tool/otel-trace"
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

describe("tool.oteltrace", () => {
  test("prepares tracing assets for mocknet by default", async () => {
    await using tmp = await tmpdir({ git: true })

    const projectDir = path.join(tmp.path, "mocknet")
    const outputDir = path.join(tmp.path, ".bootstrap", "otel", "mocknet")
    const agentJarPath = path.join(tmp.path, "fixtures", "opentelemetry-javaagent.jar")

    await fs.mkdir(projectDir, { recursive: true })
    await fs.mkdir(path.dirname(agentJarPath), { recursive: true })
    await fs.writeFile(path.join(projectDir, "pom.xml"), "<project />")
    await fs.writeFile(agentJarPath, "fake-agent")

    await Instance.provide({
      directory: tmp.path,
      fn: async () => {
        const tool = await OtelTraceTool.init()
        expect(tool.description).toContain("Do not stop at Jaeger or OTLP health alone.")
        expect(tool.description).toContain("Do not treat `ps` output alone as proof")
        expect(tool.description).toContain("Do not declare tracing healthy if Jaeger only shows queue polling spans")
        expect(tool.description).toContain("TradeSubmissionController.submitTrade")
        expect(tool.description).toContain("app-status.sh")
        const result = await tool.execute({ agentJarPath }, ctx)

        expect(result.output).toContain("Prepared OpenTelemetry tracing for mocknet.")
        expect(result.output).toContain(projectDir)
        expect(result.output).toContain("Stop app:")

        const envFile = await fs.readFile(path.join(outputDir, "otel.env"), "utf8")
        expect(envFile).toContain("export OTEL_SERVICE_NAME='mocknet'")
        expect(envFile).toContain(`-javaagent:${agentJarPath}`)

        const launcher = await fs.readFile(path.join(outputDir, "run-with-otel.sh"), "utf8")
        expect(launcher).toContain(`PID_FILE='${path.join(outputDir, "app.pid")}'`)
        expect(launcher).toContain(`LOG_FILE='${path.join(outputDir, "app.log")}'`)
        expect(launcher).toContain(`PROJECT_DIR='${projectDir}'`)
        expect(launcher).toContain(`COMMAND='mvn spring-boot:run'`)
        expect(launcher).toContain('nohup bash -lc "$COMMAND" >>"$LOG_FILE" 2>&1 &')
        expect(launcher).toContain('if [[ "${1:-}" == "--foreground" ]]; then')

        const stopApp = await fs.readFile(path.join(outputDir, "stop-app.sh"), "utf8")
        expect(stopApp).toContain(`PID_FILE='${path.join(outputDir, "app.pid")}'`)
        expect(stopApp).toContain('kill "$pid"')

        const appStatus = await fs.readFile(path.join(outputDir, "app-status.sh"), "utf8")
        expect(appStatus).toContain(`LOG_FILE='${path.join(outputDir, "app.log")}'`)
      },
    })
  })
})
