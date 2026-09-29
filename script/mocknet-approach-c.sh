#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.bootstrap/observability/approach-c"
SECRETS="$ROOT/.bootstrap/observability/grafana.env"
MODE="${MOCKNET_C_MODE:-local-demo}"
COMMAND="${1:-start}"
case "$MODE" in
  local-demo) export MOCKNET_C_DEMO_ANONYMOUS=true MOCKNET_C_ENVIRONMENT=local-demo; APP_PROFILE=observability-demo; JPA_MODE=update; GRAFANA_SCHEME=http ;;
  production) export MOCKNET_C_DEMO_ANONYMOUS=false MOCKNET_C_ENVIRONMENT=production; APP_PROFILE=production; JPA_MODE=validate; GRAFANA_SCHEME=https ;;
  *) echo 'MOCKNET_C_MODE must be local-demo or production' >&2; exit 2 ;;
esac
mkdir -p "$STATE/logs"
if [[ ( "$COMMAND" == seed || "$COMMAND" == stream-start ) && "$MODE" != local-demo ]]; then
  echo 'HTTP demo workloads are available only in local-demo mode' >&2
  exit 2
fi
if [[ "$COMMAND" == production-check && "$MODE" != production ]]; then
  echo 'Set MOCKNET_C_MODE=production for production-check' >&2
  exit 2
fi
if [[ "$MODE" == production && ( "$COMMAND" == start || "$COMMAND" == production-check ) && ! -f "$SECRETS" ]]; then
  echo 'Production mode requires an existing local secret file; start the local demo once or provision it securely.' >&2
  exit 2
fi
if [[ ! -f "$SECRETS" ]]; then (umask 077; touch "$SECRETS"); fi
for key in MOCKNET_DB_PASSWORD MOCKNET_READER_PASSWORD GRAFANA_ADMIN_PASSWORD MOCKNET_C_COLLECTOR_PASSWORD MOCKNET_C_BACKEND_DB_PASSWORD MOCKNET_C_API_READER_TOKEN MOCKNET_C_API_OPERATOR_TOKEN; do
  if ! grep -q "^${key}=" "$SECRETS"; then
    if [[ "$MODE" == production && ( "$COMMAND" == start || "$COMMAND" == production-check ) ]]; then echo "Production secret $key is missing" >&2; exit 2; fi
    (umask 077; printf '%s=%s\n' "$key" "$(openssl rand -hex 24)" >> "$SECRETS")
  fi
done
set -a
source "$SECRETS"
set +a
if [[ "$MODE" == production && ( "$COMMAND" == start || "$COMMAND" == production-check ) ]]; then
  [[ -n "${MOCKNET_C_INSTANCE:-}" && -n "${MOCKNET_C_VERSION:-}" ]] || { echo 'Set MOCKNET_C_INSTANCE and MOCKNET_C_VERSION for production telemetry' >&2; exit 2; }
  [[ "${MOCKNET_C_GRAFANA_ROOT_URL:-}" =~ ^https://[^/]+/ ]] || { echo 'Set MOCKNET_C_GRAFANA_ROOT_URL to an https:// URL ending in /' >&2; exit 2; }
  [[ "${#GRAFANA_ADMIN_PASSWORD}" -ge 20 ]] || { echo 'Production Grafana admin password must be at least 20 characters' >&2; exit 2; }
  for path in "${MOCKNET_C_GRAFANA_CERT:-}" "${MOCKNET_C_GRAFANA_KEY:-}"; do
    [[ "$path" == /* && -f "$path" && -r "$path" ]] || { echo 'Set readable absolute TLS certificate and key paths' >&2; exit 2; }
  done
  openssl x509 -in "$MOCKNET_C_GRAFANA_CERT" -noout -checkend 86400 >/dev/null || { echo 'Grafana TLS certificate is invalid or expires within 24 hours' >&2; exit 2; }
  ROOT_HOST="${MOCKNET_C_GRAFANA_ROOT_URL#https://}"
  ROOT_HOST="${ROOT_HOST%%[:/]*}"
  export MOCKNET_C_GRAFANA_HOST="$ROOT_HOST"
  openssl x509 -in "$MOCKNET_C_GRAFANA_CERT" -noout -checkhost "$ROOT_HOST" | grep -Fq 'does match certificate' || { echo 'Grafana TLS certificate does not cover root URL hostname' >&2; exit 2; }
  openssl pkey -in "$MOCKNET_C_GRAFANA_KEY" -passin pass: -noout >/dev/null 2>&1 || { echo 'Grafana TLS private key is invalid or encrypted' >&2; exit 2; }
  CERT_PUBLIC="$(openssl x509 -in "$MOCKNET_C_GRAFANA_CERT" -pubkey -noout | openssl pkey -pubin -outform DER | shasum -a 256)"
  KEY_PUBLIC="$(openssl pkey -in "$MOCKNET_C_GRAFANA_KEY" -passin pass: -pubout -outform DER | shasum -a 256)"
  [[ "$CERT_PUBLIC" == "$KEY_PUBLIC" ]] || { echo 'Grafana TLS certificate and key do not match' >&2; exit 2; }
  COMPOSE_FILES=(-f "$ROOT/observability/approach-c/compose.yaml" -f "$ROOT/observability/approach-c/compose.production.yaml")
else
  COMPOSE_FILES=(-f "$ROOT/observability/approach-c/compose.yaml")
fi
compose() { docker compose --env-file "$SECRETS" "${COMPOSE_FILES[@]}" "$@"; }
running() {
  [[ -f "$STATE/app.pid" ]] || return 1
  kill -0 "$(cat "$STATE/app.pid")" 2>/dev/null && ps -p "$(cat "$STATE/app.pid")" -o command= | grep -Fq "$STATE/app.jar"
}
ready() {
  local curl_args=(-fsS --max-time 2)
  # Resolve the configured TLS hostname to the loopback listener; curl still
  # checks the certificate chain and hostname.
  [[ "$1" == https://* ]] && curl_args+=(--resolve "$ROOT_HOST:3302:127.0.0.1")
  for _ in $(seq 1 60); do curl "${curl_args[@]}" "$1" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
case "${1:-start}" in
 production-check) compose config --quiet; echo 'Production Grafana configuration checks passed' ;;
 start)
  if running && ! ps -p "$(cat "$STATE/app.pid")" -o command= | grep -Fq -- "--spring.profiles.active=$APP_PROFILE"; then
    echo "C JVM is already running under another profile; stop it before starting $MODE" >&2
    exit 2
  fi
  # Schema provisioning uses DDL. Release long-lived reporting reads first;
  # otherwise the exporter can deadlock with view replacement.
  if [[ -x "$STATE/venv/bin/python" ]]; then
    "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" stop
    "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-telemetry.py" stop
  fi
  compose stop grafana-c
  python3 "$ROOT/script/build-mocknet-approach-c.py"
  compose up -d --wait postgres-c tempo-c loki-c prometheus-c
  if [[ ! -x "$STATE/venv/bin/python" ]]; then python3 -m venv "$STATE/venv"; fi
  "$STATE/venv/bin/pip" install -q -r "$ROOT/observability/approach-c/backend/requirements.txt"
  if ! running; then
    if lsof -nP -iTCP:18101 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Port 18101 is occupied' >&2; exit 1; fi
    mvn -q -f "$ROOT/mocknet/pom.xml" -DskipTests package
    cp "$ROOT/mocknet/target/mocknet-mock-1.0.0-SNAPSHOT.jar" "$STATE/app.jar"
    export SPRING_DATASOURCE_PASSWORD="$MOCKNET_DB_PASSWORD"
    export MOCKNET_COMPONENT_LOG_DIR="$STATE/logs"
    if [[ "$MODE" == production ]]; then
      export MOCKNET_INSTANCE="$MOCKNET_C_INSTANCE" MOCKNET_VERSION="$MOCKNET_C_VERSION"
    else
      export MOCKNET_INSTANCE=c-local-1 MOCKNET_VERSION=c-parity-v1
    fi
    python3 "$ROOT/script/mocknet-detach.py" "$STATE" java -Xms128m -Xmx512m -jar "$STATE/app.jar" \
      --server.address=127.0.0.1 --server.port=18101 --management.server.address=127.0.0.1 --management.server.port=18102 \
      --management.endpoints.web.exposure.include=health,prometheus --management.prometheus.metrics.export.enabled=true \
      --spring.profiles.active="$APP_PROFILE" --spring.h2.console.enabled=false \
      --spring.jpa.hibernate.ddl-auto="$JPA_MODE" \
      --spring.datasource.url=jdbc:postgresql://127.0.0.1:15452/mocknet_c \
      --spring.datasource.driver-class-name=org.postgresql.Driver --spring.datasource.username=mocknet_c \
      --mocknet.tracing.enabled=false --mocknet.component-journal.enabled=true \
      --logging.config="$ROOT/observability/approach-c/logback.xml"
  fi
  ready http://127.0.0.1:18101/api/status
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-store.py"
  compose up -d --wait grafana-c
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-api.py" start
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" start
  ready http://127.0.0.1:3202/ready
  ready http://127.0.0.1:13102/ready
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-telemetry.py" start
  if [[ "$MODE" == production ]]; then
    ready "https://$ROOT_HOST:3302/api/health"
  else
    ready http://127.0.0.1:3302/api/health
  fi
  echo 'Approach C backend: http://localhost:18101/api/status'
  echo "Approach C Grafana: ${GRAFANA_SCHEME}://localhost:3302/d/mocknet-c-business"
  echo 'Approach C support API: http://localhost:18103/health'
  ;;
 stop)
  python3 "$ROOT/script/mocknet-stream.py" stop --approach C
  if running; then kill "$(cat "$STATE/app.pid")"; for _ in $(seq 1 30); do running || break; sleep 1; done; fi
  if running; then echo 'C JVM did not stop' >&2; exit 1; fi
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" stop
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-api.py" stop
  "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-telemetry.py" stop
  compose down
  ;;
 status) compose ps; if running; then echo "C JVM PID $(cat "$STATE/app.pid")"; fi; "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" status; "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-api.py" status; "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-telemetry.py" status ;;
 stream-start) python3 "$ROOT/script/mocknet-stream.py" start --approach C --rate "${MOCKNET_SEED_RATE:-1}" ;;
 stream-stop) python3 "$ROOT/script/mocknet-stream.py" stop --approach C ;;
 stream-status) python3 "$ROOT/script/mocknet-stream.py" status --approach C ;;
 seed) [[ "$MODE" == local-demo ]] || { echo 'HTTP demo seed is available only in local-demo mode' >&2; exit 2; }; "$STATE/venv/bin/python" "$ROOT/script/seed-mocknet-c-business.py" "${@:2}" ;;
 reporter-start|reporter-stop) "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-reporter.py" "${1#reporter-}" ;;
 telemetry-start|telemetry-stop) "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-telemetry.py" "${1#telemetry-}" ;;
 api-start|api-stop) "$STATE/venv/bin/python" "$ROOT/script/mocknet-c-api.py" "${1#api-}" ;;
 *) echo 'Usage: mocknet-approach-c.sh start|stop|status|seed|production-check|stream-start|stream-stop|stream-status|reporter-start|reporter-stop|api-start|api-stop|telemetry-start|telemetry-stop'; exit 1 ;;
esac
