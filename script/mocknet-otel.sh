#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOCKNET_DIR="$ROOT_DIR/mocknet"
OTEL_DIR="$ROOT_DIR/.bootstrap/otel/mocknet"
AGENT_JAR="$OTEL_DIR/opentelemetry-javaagent.jar"
ENV_FILE="$OTEL_DIR/otel.env"
APP_PID_FILE="$OTEL_DIR/app.pid"
APP_LOG_FILE="$OTEL_DIR/app.log"
JAEGER_CONTAINER="bootstrap-jaeger-mocknet"
MOCKNET_OTLP_ENDPOINT="${MOCKNET_OTLP_ENDPOINT:-http://localhost:4318}"
OTEL_SERVICE_NAME_VALUE="${OTEL_SERVICE_NAME_VALUE:-mocknet}"
APP_COMMAND="${APP_COMMAND:-mvn spring-boot:run}"
AGENT_URL="${AGENT_URL:-https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar}"

usage() {
  cat <<'EOF'
Usage: script/mocknet-otel.sh [start|stop|restart|status|logs]

Commands:
  start    Start Jaeger and mocknet with the OpenTelemetry Java agent
  stop     Stop mocknet and remove the Jaeger container
  restart  Restart both services
  status   Print Jaeger and mocknet status
  logs     Tail the mocknet application log
EOF
}

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

ensure_dirs() {
  mkdir -p "$OTEL_DIR"
}

is_pid_running() {
  local pid="${1:-}"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

wait_for_http() {
  local url="$1"
  local label="$2"
  for _ in $(seq 1 60); do
    if curl --max-time 1 -s -o /dev/null "$url"; then
      return 0
    fi
    sleep 1
  done
  echo "$label did not become ready: $url" >&2
  exit 1
}

write_env_file() {
  cat >"$ENV_FILE" <<EOF
export OTEL_SERVICE_NAME='$OTEL_SERVICE_NAME_VALUE'
export OTEL_TRACES_EXPORTER='otlp'
export OTEL_METRICS_EXPORTER='none'
export OTEL_LOGS_EXPORTER='none'
export OTEL_EXPORTER_OTLP_PROTOCOL='http/protobuf'
export OTEL_EXPORTER_OTLP_ENDPOINT='$MOCKNET_OTLP_ENDPOINT'
export OTEL_JAVAAGENT_LOGGING='application'
export JAVA_TOOL_OPTIONS='-javaagent:$AGENT_JAR \${JAVA_TOOL_OPTIONS:-}'
EOF
}

ensure_agent() {
  if [[ -f "$AGENT_JAR" ]]; then
    return 0
  fi

  require_command curl
  ensure_dirs
  echo "Downloading OpenTelemetry Java agent..."
  curl -fL "$AGENT_URL" -o "$AGENT_JAR"
}

jaeger_is_running() {
  if ! command -v docker >/dev/null 2>&1; then
    return 1
  fi
  docker ps --filter "name=^/${JAEGER_CONTAINER}$" --filter "status=running" --quiet | grep -q .
}

start_jaeger() {
  require_command docker

  if jaeger_is_running; then
    echo "Jaeger is already running in container $JAEGER_CONTAINER."
  else
    docker rm -f "$JAEGER_CONTAINER" >/dev/null 2>&1 || true
    docker run -d \
      --name "$JAEGER_CONTAINER" \
      -e COLLECTOR_OTLP_ENABLED=true \
      -p 16686:16686 \
      -p 4317:4317 \
      -p 4318:4318 \
      jaegertracing/all-in-one:latest >/dev/null
    echo "Started Jaeger in container $JAEGER_CONTAINER."
  fi

  wait_for_http "http://localhost:16686/" "Jaeger UI"
  wait_for_http "$MOCKNET_OTLP_ENDPOINT/" "Jaeger OTLP endpoint"
  echo "Jaeger UI: http://localhost:16686"
}

stop_jaeger() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Skipping Jaeger stop because docker is not installed."
    return 0
  fi
  docker rm -f "$JAEGER_CONTAINER" >/dev/null 2>&1 || true
  echo "Stopped Jaeger container $JAEGER_CONTAINER."
}

start_app() {
  require_command mvn
  ensure_dirs
  ensure_agent
  write_env_file

  if [[ -f "$APP_PID_FILE" ]]; then
    local existing_pid
    existing_pid="$(cat "$APP_PID_FILE" 2>/dev/null || true)"
    if is_pid_running "$existing_pid"; then
      echo "mocknet is already running with PID $existing_pid."
      echo "Logs: $APP_LOG_FILE"
      return 0
    fi
    rm -f "$APP_PID_FILE"
  fi

  export OTEL_SERVICE_NAME="$OTEL_SERVICE_NAME_VALUE"
  export OTEL_TRACES_EXPORTER="otlp"
  export OTEL_METRICS_EXPORTER="none"
  export OTEL_LOGS_EXPORTER="none"
  export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
  export OTEL_EXPORTER_OTLP_ENDPOINT="$MOCKNET_OTLP_ENDPOINT"
  export OTEL_JAVAAGENT_LOGGING="application"
  export JAVA_TOOL_OPTIONS="-javaagent:$AGENT_JAR${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"

  touch "$APP_LOG_FILE"

  (
    cd "$MOCKNET_DIR"
    nohup bash -lc "$APP_COMMAND" >>"$APP_LOG_FILE" 2>&1 &
    echo $! >"$APP_PID_FILE"
  )

  local app_pid
  app_pid="$(cat "$APP_PID_FILE")"

  for _ in $(seq 1 40); do
    if ! is_pid_running "$app_pid"; then
      echo "mocknet failed to stay up. Recent logs:" >&2
      tail -n 40 "$APP_LOG_FILE" >&2 || true
      rm -f "$APP_PID_FILE"
      exit 1
    fi
    if grep -Eq "Tomcat started on port|Started .* in [0-9]" "$APP_LOG_FILE"; then
      echo "mocknet started with PID $app_pid."
      echo "Logs: $APP_LOG_FILE"
      return 0
    fi
    sleep 0.5
  done

  echo "mocknet launched with PID $app_pid and is still starting."
  echo "Logs: $APP_LOG_FILE"
}

stop_app() {
  if [[ ! -f "$APP_PID_FILE" ]]; then
    echo "mocknet is not running."
    return 0
  fi

  local pid
  pid="$(cat "$APP_PID_FILE" 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    rm -f "$APP_PID_FILE"
    echo "Removed empty PID file."
    return 0
  fi

  if is_pid_running "$pid"; then
    kill "$pid"
    for _ in $(seq 1 20); do
      if ! is_pid_running "$pid"; then
        rm -f "$APP_PID_FILE"
        echo "Stopped mocknet PID $pid."
        return 0
      fi
      sleep 0.5
    done
    kill -9 "$pid" >/dev/null 2>&1 || true
    rm -f "$APP_PID_FILE"
    echo "Force-stopped mocknet PID $pid."
    return 0
  fi

  rm -f "$APP_PID_FILE"
  echo "Removed stale PID file for mocknet PID $pid."
}

status() {
  if jaeger_is_running; then
    echo "Jaeger: running ($JAEGER_CONTAINER)"
  elif command -v docker >/dev/null 2>&1; then
    echo "Jaeger: stopped"
  else
    echo "Jaeger: unavailable (docker not installed)"
  fi

  if [[ -f "$APP_PID_FILE" ]]; then
    local pid
    pid="$(cat "$APP_PID_FILE" 2>/dev/null || true)"
    if is_pid_running "$pid"; then
      echo "mocknet: running (PID $pid)"
      echo "Logs: $APP_LOG_FILE"
      return 0
    fi
    echo "mocknet: stale PID file ($pid)"
    echo "Logs: $APP_LOG_FILE"
    return 1
  fi

  echo "mocknet: stopped"
  echo "Logs: $APP_LOG_FILE"
}

logs() {
  ensure_dirs
  touch "$APP_LOG_FILE"
  tail -n 200 -f "$APP_LOG_FILE"
}

command="${1:-start}"

case "$command" in
  start)
    start_jaeger
    start_app
    ;;
  stop)
    stop_app
    stop_jaeger
    ;;
  restart)
    stop_app
    stop_jaeger
    start_jaeger
    start_app
    ;;
  status)
    status
    ;;
  logs)
    logs
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
