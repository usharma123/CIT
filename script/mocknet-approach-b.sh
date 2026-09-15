#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.bootstrap/observability/approach-b"
SECRETS="$ROOT/.bootstrap/observability/grafana.env"
JAR="$STATE/app.jar"
AGENT="$ROOT/.bootstrap/observability/opentelemetry-javaagent-2.26.1.jar"
mkdir -p "$STATE"
set -a
source "$SECRETS"
set +a
compose() { docker compose --env-file "$SECRETS" -f "$ROOT/observability/approach-b/compose.yaml" "$@"; }
running() {
  [[ -f "$STATE/app.pid" ]] || return 1
  local pid; pid="$(cat "$STATE/app.pid")"
  kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o command= | grep -Fq "$JAR"
}
ready() {
  for _ in $(seq 1 60); do curl -fsS --max-time 2 "$1" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "Not ready: $1" >&2; return 1
}
case "${1:-start}" in
 start)
  compose up -d
  ready http://127.0.0.1:13143/
  if ! running; then
   if lsof -nP -iTCP:18091 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Port 18091 is occupied' >&2; exit 1; fi
   mvn -q -f "$ROOT/mocknet/pom.xml" -DskipTests package
   cp "$ROOT/mocknet/target/mocknet-mock-1.0.0-SNAPSHOT.jar" "$JAR"
   printf '%s  %s\n' cc4af5966ab72109cacc962ba3b9f99b3e88caf064c3144a451bcfe0f4950f19 "$AGENT" | shasum -a 256 -c -
   export OTEL_SERVICE_NAME=mocknet-b
   export OTEL_RESOURCE_ATTRIBUTES="service.version=b-jvm-v1,service.instance.id=b-local-1,deployment.environment.name=local-demo"
   export OTEL_TRACES_EXPORTER=none OTEL_TRACES_SAMPLER=always_off OTEL_LOGS_EXPORTER=none
   export OTEL_METRICS_EXPORTER=otlp OTEL_METRIC_EXPORT_INTERVAL=5000
   export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14328 OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
   export OTEL_METRICS_EXEMPLAR_FILTER=always_off
   export OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false
   export OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED=true
   export OTEL_INSTRUMENTATION_MICROMETER_ENABLED=false
   export OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_EMIT_EXPERIMENTAL_METRICS=true
   export SPRING_DATASOURCE_PASSWORD="$MOCKNET_DB_PASSWORD"
   export MOCKNET_VERSION=b-jvm-v1 MOCKNET_INSTANCE=b-local-1
   python3 "$ROOT/script/mocknet-detach.py" "$STATE" java -Xms128m -Xmx512m "-javaagent:$AGENT" -jar "$JAR" \
    --server.address=127.0.0.1 --server.port=18091 --management.server.port=18092 \
    --management.endpoints.web.exposure.include=health --management.prometheus.metrics.export.enabled=false \
    --spring.profiles.active=observability-demo --spring.h2.console.enabled=false \
    --spring.datasource.url=jdbc:postgresql://127.0.0.1:15442/mocknet_b \
    --spring.datasource.driver-class-name=org.postgresql.Driver --spring.datasource.username=mocknet_b \
    --mocknet.tracing.enabled=false --logging.level.root=WARN
  fi
  ready http://127.0.0.1:18091/api/status
  echo 'Approach B backend: http://localhost:18091/api/status'
  ;;
 stop)
  python3 "$ROOT/script/mocknet-stream.py" stop --approach B
  if running; then kill "$(cat "$STATE/app.pid")"; for _ in $(seq 1 30); do running || break; sleep 1; done; fi
  if running; then echo 'Approach B JVM did not stop' >&2; exit 1; fi
  rm -f "$STATE/app.pid"
  compose down
  ;;
 status) compose ps; if running; then echo "Approach B JVM PID $(cat "$STATE/app.pid")"; else echo 'Approach B stopped'; fi ;;
 stream-start) python3 "$ROOT/script/mocknet-stream.py" start --approach B --rate "${MOCKNET_SEED_RATE:-1}" ;;
 stream-stop) python3 "$ROOT/script/mocknet-stream.py" stop --approach B ;;
 stream-status) python3 "$ROOT/script/mocknet-stream.py" status --approach B ;;
 *) echo 'Usage: mocknet-approach-b.sh start|stop|status|stream-start|stream-stop|stream-status'; exit 1 ;;
esac
