#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.bootstrap/observability/approach-c"
SECRETS="$ROOT/.bootstrap/observability/grafana.env"
mkdir -p "$STATE/logs"
if ! grep -q '^MOCKNET_C_COLLECTOR_PASSWORD=' "$SECRETS"; then
  (umask 077; printf 'MOCKNET_C_COLLECTOR_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> "$SECRETS")
fi
set -a
source "$SECRETS"
set +a
compose() { docker compose --env-file "$SECRETS" -f "$ROOT/observability/approach-c/compose.yaml" "$@"; }
running() {
  [[ -f "$STATE/app.pid" ]] || return 1
  kill -0 "$(cat "$STATE/app.pid")" 2>/dev/null && ps -p "$(cat "$STATE/app.pid")" -o command= | grep -Fq "$STATE/app.jar"
}
ready() { for _ in $(seq 1 60); do curl -fsS --max-time 2 "$1" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
case "${1:-start}" in
 start)
  compose up -d --wait
  if [[ ! -x "$STATE/venv/bin/python" ]]; then python3 -m venv "$STATE/venv"; fi
  "$STATE/venv/bin/python" -c 'import psycopg' 2>/dev/null || "$STATE/venv/bin/pip" install 'psycopg[binary]==3.2.10'
  if ! running; then
    if lsof -nP -iTCP:18101 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Port 18101 is occupied' >&2; exit 1; fi
    mvn -q -f "$ROOT/mocknet/pom.xml" -DskipTests package
    cp "$ROOT/mocknet/target/mocknet-mock-1.0.0-SNAPSHOT.jar" "$STATE/app.jar"
    export SPRING_DATASOURCE_PASSWORD="$MOCKNET_DB_PASSWORD"
    export MOCKNET_COMPONENT_LOG_DIR="$STATE/logs" MOCKNET_INSTANCE=c-local-1 MOCKNET_VERSION=c-journal-v1
    python3 "$ROOT/script/mocknet-detach.py" "$STATE" java -Xms128m -Xmx512m -jar "$STATE/app.jar" \
      --server.address=127.0.0.1 --server.port=18101 --management.server.port=18102 \
      --management.endpoints.web.exposure.include=health --management.prometheus.metrics.export.enabled=false \
      --spring.profiles.active=observability-demo --spring.h2.console.enabled=false \
      --spring.datasource.url=jdbc:postgresql://127.0.0.1:15452/mocknet_c \
      --spring.datasource.driver-class-name=org.postgresql.Driver --spring.datasource.username=mocknet_c \
      --mocknet.tracing.enabled=false --mocknet.component-journal.enabled=true \
      --logging.config="$ROOT/observability/approach-c/logback.xml"
  fi
  ready http://127.0.0.1:18101/api/status
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-store.py"
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" start
  ready http://127.0.0.1:3302/api/health
  echo 'Approach C backend: http://localhost:18101/api/status'
  echo 'Approach C Grafana: http://localhost:3302/d/mocknet-c-overview'
  ;;
 stop)
  python3 "$ROOT/script/mocknet-stream.py" stop --approach C
  if running; then kill "$(cat "$STATE/app.pid")"; for _ in $(seq 1 30); do running || break; sleep 1; done; fi
  if running; then echo 'C JVM did not stop' >&2; exit 1; fi
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" stop
  compose down
  ;;
 status) compose ps; if running; then echo "C JVM PID $(cat "$STATE/app.pid")"; fi; "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" status ;;
 stream-start) python3 "$ROOT/script/mocknet-stream.py" start --approach C --rate "${MOCKNET_SEED_RATE:-1}" ;;
 stream-stop) python3 "$ROOT/script/mocknet-stream.py" stop --approach C ;;
 stream-status) python3 "$ROOT/script/mocknet-stream.py" status --approach C ;;
 reporter-start|reporter-stop) "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" "${1#reporter-}" ;;
 *) echo 'Usage: mocknet-approach-c.sh start|stop|status|stream-start|stream-stop|stream-status|reporter-start|reporter-stop'; exit 1 ;;
esac
