import fs from "fs/promises"
import { existsSync } from "fs"
import path from "path"
import z from "zod"
import { Tool } from "./tool"
import DESCRIPTION from "./otel-trace.txt"
import { Instance } from "@/project/instance"

const DEFAULT_AGENT_URL =
  "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar"
const DEFAULT_OTLP_ENDPOINT = "http://localhost:4318"

export const OtelTraceTool = Tool.define("oteltrace", async () => {
  return {
    description: DESCRIPTION.replaceAll("${directory}", Instance.directory),
    parameters: z.object({
      projectDir: z.string().describe("Java project root to instrument").optional(),
      serviceName: z.string().describe("Trace service name").optional(),
      command: z.string().describe("Command used to launch the Java application").optional(),
      otlpEndpoint: z.string().describe("OTLP HTTP endpoint").optional(),
      agentJarPath: z.string().describe("Existing local OpenTelemetry Java agent JAR").optional(),
      agentJarUrl: z.string().describe("Alternate download URL for the Java agent").optional(),
      outputDir: z.string().describe("Directory for generated tracing assets").optional(),
    }),
    async execute(params) {
      const projectDir = await resolveProjectDir(params.projectDir)
      const serviceName = params.serviceName ?? path.basename(projectDir)
      const command = params.command ?? (await detectDefaultCommand(projectDir))
      const otlpEndpoint = params.otlpEndpoint ?? DEFAULT_OTLP_ENDPOINT
      const outputDir = path.resolve(
        params.outputDir ?? path.join(Instance.directory, ".bootstrap", "otel", serviceName),
      )

      await fs.mkdir(outputDir, { recursive: true })

      const agentJarPath = await ensureAgentJar({
        outputDir,
        explicitAgentJarPath: params.agentJarPath,
        downloadUrl: params.agentJarUrl ?? DEFAULT_AGENT_URL,
      })

      const envPath = path.join(outputDir, "otel.env")
      const launchPath = path.join(outputDir, "run-with-otel.sh")
      const appStopPath = path.join(outputDir, "stop-app.sh")
      const appStatusPath = path.join(outputDir, "app-status.sh")
      const jaegerStartPath = path.join(outputDir, "start-jaeger.sh")
      const jaegerStopPath = path.join(outputDir, "stop-jaeger.sh")
      const containerName = `bootstrap-jaeger-${sanitizeName(serviceName)}`
      const pidPath = path.join(outputDir, "app.pid")
      const logPath = path.join(outputDir, "app.log")

      await fs.writeFile(
        envPath,
        [
          `export OTEL_SERVICE_NAME=${shellQuote(serviceName)}`,
          "export OTEL_TRACES_EXPORTER=otlp",
          "export OTEL_METRICS_EXPORTER=none",
          "export OTEL_LOGS_EXPORTER=none",
          "export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf",
          `export OTEL_EXPORTER_OTLP_ENDPOINT=${shellQuote(otlpEndpoint)}`,
          "export OTEL_JAVAAGENT_LOGGING=application",
          `export JAVA_TOOL_OPTIONS="-javaagent:${agentJarPath} \${JAVA_TOOL_OPTIONS:-}"`,
          "",
        ].join("\n"),
      )

      await writeExecutable(
        launchPath,
        [
          "#!/usr/bin/env bash",
          "set -euo pipefail",
          `PID_FILE=${shellQuote(pidPath)}`,
          `LOG_FILE=${shellQuote(logPath)}`,
          `PROJECT_DIR=${shellQuote(projectDir)}`,
          `COMMAND=${shellQuote(command)}`,
          "",
          "if [[ \"${1:-}\" == \"--foreground\" ]]; then",
          `  source ${shellQuote(envPath)}`,
          "  cd \"$PROJECT_DIR\"",
          "  exec bash -lc \"$COMMAND\"",
          "fi",
          "",
          'is_running() {',
          '  local pid="${1:-}"',
          '  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1',
          '}',
          "",
          'if [[ -f "$PID_FILE" ]]; then',
          '  existing_pid="$(cat "$PID_FILE" 2>/dev/null || true)"',
          '  if is_running "$existing_pid"; then',
          '    echo "Application is already running with PID $existing_pid."',
          '    echo "Logs: $LOG_FILE"',
          '    exit 0',
          "  fi",
          '  rm -f "$PID_FILE"',
          "fi",
          "",
          `source ${shellQuote(envPath)}`,
          "mkdir -p \"$(dirname \"$LOG_FILE\")\"",
          "touch \"$LOG_FILE\"",
          "cd \"$PROJECT_DIR\"",
          'nohup bash -lc "$COMMAND" >>"$LOG_FILE" 2>&1 &',
          'app_pid=$!',
          'echo "$app_pid" > "$PID_FILE"',
          "",
          'for _ in $(seq 1 40); do',
          '  if ! is_running "$app_pid"; then',
          '    echo "Application failed to stay up. Recent logs:"',
          '    tail -n 40 "$LOG_FILE" || true',
          '    rm -f "$PID_FILE"',
          '    exit 1',
          "  fi",
          '  if grep -Eq "Tomcat started on port|Started .* in [0-9]" "$LOG_FILE"; then',
          '    echo "Application started with PID $app_pid."',
          '    echo "Logs: $LOG_FILE"',
          '    exit 0',
          "  fi",
          "  sleep 0.5",
          "done",
          "",
          'echo "Application launched with PID $app_pid and is still starting."',
          'echo "Logs: $LOG_FILE"',
          "",
        ].join("\n"),
      )

      await writeExecutable(
        appStopPath,
        [
          "#!/usr/bin/env bash",
          "set -euo pipefail",
          `PID_FILE=${shellQuote(pidPath)}`,
          "",
          'if [[ ! -f "$PID_FILE" ]]; then',
          '  echo "Application is not running."',
          "  exit 0",
          "fi",
          "",
          'pid="$(cat "$PID_FILE" 2>/dev/null || true)"',
          'if [[ -z "$pid" ]]; then',
          '  rm -f "$PID_FILE"',
          '  echo "Removed empty PID file."',
          "  exit 0",
          "fi",
          "",
          'if kill -0 "$pid" >/dev/null 2>&1; then',
          '  kill "$pid"',
          '  for _ in $(seq 1 20); do',
          '    if ! kill -0 "$pid" >/dev/null 2>&1; then',
          '      rm -f "$PID_FILE"',
          '      echo "Stopped application PID $pid."',
          '      exit 0',
          "    fi",
          "    sleep 0.5",
          "  done",
          '  kill -9 "$pid" >/dev/null 2>&1 || true',
          '  rm -f "$PID_FILE"',
          '  echo "Force-stopped application PID $pid."',
          '  exit 0',
          "fi",
          "",
          'rm -f "$PID_FILE"',
          'echo "Removed stale PID file for PID $pid."',
          "",
        ].join("\n"),
      )

      await writeExecutable(
        appStatusPath,
        [
          "#!/usr/bin/env bash",
          "set -euo pipefail",
          `PID_FILE=${shellQuote(pidPath)}`,
          `LOG_FILE=${shellQuote(logPath)}`,
          "",
          'if [[ ! -f "$PID_FILE" ]]; then',
          '  echo "Application is not running."',
          '  echo "Logs: $LOG_FILE"',
          "  exit 1",
          "fi",
          "",
          'pid="$(cat "$PID_FILE" 2>/dev/null || true)"',
          'if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then',
          '  echo "Application is running with PID $pid."',
          '  echo "Logs: $LOG_FILE"',
          '  exit 0',
          "fi",
          "",
          'echo "Application is not running, but a stale PID file exists."',
          'echo "Logs: $LOG_FILE"',
          'exit 1',
          "",
        ].join("\n"),
      )

      await writeExecutable(
        jaegerStartPath,
        [
          "#!/usr/bin/env bash",
          "set -euo pipefail",
          `docker rm -f ${shellQuote(containerName)} >/dev/null 2>&1 || true`,
          [
            "docker run -d",
            `--name ${shellQuote(containerName)}`,
            "-e COLLECTOR_OTLP_ENABLED=true",
            "-p 16686:16686",
            "-p 4317:4317",
            "-p 4318:4318",
            "jaegertracing/all-in-one:latest",
          ].join(" \\\n  "),
          'echo "Jaeger UI: http://localhost:16686"',
          "",
        ].join("\n"),
      )

      await writeExecutable(
        jaegerStopPath,
        [
          "#!/usr/bin/env bash",
          "set -euo pipefail",
          `docker rm -f ${shellQuote(containerName)}`,
          "",
        ].join("\n"),
      )

      const output = [
        `Prepared OpenTelemetry tracing for ${serviceName}.`,
        `Project: ${projectDir}`,
        `Agent JAR: ${agentJarPath}`,
        `Launch app: bash ${shellQuote(launchPath)}`,
        `Launch in foreground: bash ${shellQuote(launchPath)} --foreground`,
        `App status: bash ${shellQuote(appStatusPath)}`,
        `Stop app: bash ${shellQuote(appStopPath)}`,
        `Start Jaeger: bash ${shellQuote(jaegerStartPath)}`,
        `Stop Jaeger: bash ${shellQuote(jaegerStopPath)}`,
        `App logs: ${logPath}`,
        "Jaeger UI: http://localhost:16686",
      ].join("\n")

      return {
        title: `Prepared tracing for ${serviceName}`,
        metadata: {
          projectDir,
          serviceName,
          agentJarPath,
          launchPath,
          appStopPath,
          appStatusPath,
          jaegerStartPath,
          jaegerStopPath,
          envPath,
          pidPath,
          logPath,
          command,
          otlpEndpoint,
        },
        output,
      }
    },
  }
})

async function resolveProjectDir(projectDir?: string) {
  const preferred = projectDir ? path.resolve(projectDir) : path.join(Instance.directory, "mocknet")

  if (await isJavaProject(preferred)) {
    return preferred
  }

  if (!projectDir && (await isJavaProject(Instance.directory))) {
    return Instance.directory
  }

  throw new Error(`Could not find a Java project at ${preferred}. Expected pom.xml or build.gradle(.kts).`)
}

async function isJavaProject(projectDir: string) {
  return (
    existsSync(path.join(projectDir, "pom.xml")) ||
    existsSync(path.join(projectDir, "build.gradle")) ||
    existsSync(path.join(projectDir, "build.gradle.kts"))
  )
}

async function detectDefaultCommand(projectDir: string) {
  if (existsSync(path.join(projectDir, "pom.xml"))) {
    return "mvn spring-boot:run"
  }
  if (existsSync(path.join(projectDir, "build.gradle")) || existsSync(path.join(projectDir, "build.gradle.kts"))) {
    return "./gradlew bootRun"
  }
  throw new Error(`Could not infer a Java launch command for ${projectDir}. Pass command explicitly.`)
}

async function ensureAgentJar(input: {
  outputDir: string
  explicitAgentJarPath?: string
  downloadUrl: string
}) {
  if (input.explicitAgentJarPath) {
    const resolved = path.resolve(input.explicitAgentJarPath)
    if (!existsSync(resolved)) {
      throw new Error(`OpenTelemetry Java agent JAR not found at ${resolved}`)
    }
    return resolved
  }

  const target = path.join(input.outputDir, "opentelemetry-javaagent.jar")
  if (existsSync(target)) {
    return target
  }

  const response = await fetch(input.downloadUrl)
  if (!response.ok) {
    throw new Error(`Failed to download OpenTelemetry Java agent from ${input.downloadUrl}: ${response.status}`)
  }

  await fs.writeFile(target, Buffer.from(await response.arrayBuffer()))
  return target
}

async function writeExecutable(filepath: string, content: string) {
  await fs.writeFile(filepath, content)
  await fs.chmod(filepath, 0o755)
}

function sanitizeName(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "service"
}

function shellQuote(value: string) {
  return `'${value.replaceAll("'", `'\"'\"'`)}'`
}
