#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.bootstrap/observability"
BUILD_JAR="$ROOT/mocknet/target/mocknet-mock-1.0.0-SNAPSHOT.jar"
JAR="$STATE/app-runtime.jar"
AGENT="$STATE/opentelemetry-javaagent-2.26.1.jar"
mkdir -p "$STATE"
if [[ ! -f "$STATE/grafana.env" ]]; then
  (umask 077; printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)" > "$STATE/grafana.env")
fi
# Separate generated secrets for app, read-only support views, and fault controls.
for key in MOCKNET_DB_PASSWORD MOCKNET_READER_PASSWORD MOCKNET_DEMO_TOKEN; do
  if ! grep -q "^${key}=" "$STATE/grafana.env"; then
    (umask 077; printf '%s=%s\n' "$key" "$(openssl rand -hex 24)" >> "$STATE/grafana.env")
  fi
done
set -a
source "$STATE/grafana.env"
set +a
compose() { docker compose --env-file "$STATE/grafana.env" -f "$ROOT/observability/compose.yaml" "$@"; }
ready() {
  local url="$1"
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Readiness failed: $url" >&2
  return 1
}
app_running() {
  [[ -f "$STATE/app.pid" ]] || return 1
  local pid
  pid="$(cat "$STATE/app.pid")"
  kill -0 "$pid" 2>/dev/null || return 1
  local command
  command="$(ps -p "$pid" -o command=)"
  # Recognize the former launcher path while migrating existing local runs.
  [[ "$command" == *"-jar $JAR "* || "$command" == *"-jar $BUILD_JAR "* ]]
}
case "${1:-start}" in
  start)
    compose up -d
    ready http://127.0.0.1:3200/ready
    ready http://127.0.0.1:13133/
    ready http://127.0.0.1:3300/api/health
    if ! app_running; then
      if lsof -nP -iTCP:18081 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Port 18081 is already occupied' >&2; exit 1; fi
      if [[ -n "${MOCKNET_APP_JAR_SOURCE:-}" ]]; then
        [[ -f "$MOCKNET_APP_JAR_SOURCE" ]] || { echo 'Requested app artifact is missing' >&2; exit 1; }
        cp "$MOCKNET_APP_JAR_SOURCE" "$JAR"
      else
        mvn -q -f "$ROOT/mocknet/pom.xml" -DskipTests package
        cp "$BUILD_JAR" "$JAR"
      fi
      if [[ ! -f "$AGENT" ]]; then
        curl -fL --retry 3 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar -o "$AGENT.tmp"
        mv "$AGENT.tmp" "$AGENT"
      fi
      printf '%s  %s\n' cc4af5966ab72109cacc962ba3b9f99b3e88caf064c3144a451bcfe0f4950f19 "$AGENT" | shasum -a 256 -c -
      export OTEL_SERVICE_NAME=mocknet
      export OTEL_RESOURCE_ATTRIBUTES="service.version=${MOCKNET_VERSION:-l3-demo-v1},service.instance.id=${MOCKNET_INSTANCE:-local-1},deployment.environment.name=local-demo"
      export OTEL_TRACES_EXPORTER=otlp OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=otlp
      export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318
      export OTEL_TRACES_SAMPLER=parentbased_always_on OTEL_BSP_SCHEDULE_DELAY=1000
      export OTEL_BSP_MAX_QUEUE_SIZE=4096 OTEL_BSP_MAX_EXPORT_BATCH_SIZE=512
      export OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT=1024
      # Preserve SQL detail for investigating request and worker latency.
      export OTEL_INSTRUMENTATION_JDBC_ENABLED=true
      export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true
      export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=operation_id,business_id
      # JDBC spans retain dependency timing; disable duplicate framework repository spans.
      export OTEL_INSTRUMENTATION_SPRING_DATA_ENABLED=false OTEL_INSTRUMENTATION_HIBERNATE_ENABLED=false
      export SPRING_DATASOURCE_PASSWORD="$MOCKNET_DB_PASSWORD"
      export MOCKNET_DEMO_TOKEN
      if [[ -f "$STATE/app.log" ]]; then mv "$STATE/app.log" "$STATE/app-$(date +%Y%m%dT%H%M%S).log"; fi
      python3 "$ROOT/script/mocknet-detach.py" "$STATE" java "-javaagent:$AGENT" -jar "$JAR" \
        --server.address=127.0.0.1 --server.port=18081 \
        --spring.profiles.active=observability-demo \
        --spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/mocknet \
        --spring.datasource.driver-class-name=org.postgresql.Driver \
        --spring.datasource.username=mocknet \
        --spring.h2.console.enabled=false \
        --logging.level.root=WARN --logging.level.com.cit.mocknet.observability=INFO
    fi
    ready http://127.0.0.1:18081/api/status
    bash "$ROOT/script/mocknet-support-views.sh"
    echo 'Backend: http://localhost:18081/api/status'
    echo 'Grafana: http://localhost:3300/d/mocknet-traces'
    ;;
  stop)
    python3 "$ROOT/script/mocknet-stream.py" stop
    if app_running; then
      kill "$(cat "$STATE/app.pid")"
      for _ in $(seq 1 30); do app_running || break; sleep 1; done
      if app_running; then echo 'Backend did not stop within 30 seconds' >&2; exit 1; fi
    fi
    rm -f "$STATE/app.pid"
    compose down
    ;;
  status)
    compose ps
    if app_running; then echo "Backend PID $(cat "$STATE/app.pid")"; else echo 'Backend is stopped'; fi
    curl -fsS http://127.0.0.1:18081/api/status
    ;;
  stream-start) python3 "$ROOT/script/mocknet-stream.py" start --rate "${MOCKNET_SEED_RATE:-1}" ;;
  stream-stop) python3 "$ROOT/script/mocknet-stream.py" stop ;;
  stream-status) python3 "$ROOT/script/mocknet-stream.py" status ;;
  l3-demo) python3 "$ROOT/script/mocknet-l3-demo.py" ;;
  check) python3 "$ROOT/script/check-mocknet-dashboards.py" ;;
  demo) python3 "$ROOT/script/mocknet-grafana-demo.py" ;;
  logs) tail -n 100 -f "$STATE/app.log" ;;
  *) echo 'Usage: script/mocknet-grafana.sh start|stop|status|demo|l3-demo|check|stream-start|stream-stop|stream-status|logs'; exit 1 ;;
esac
