#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/.bootstrap/observability/grafana.env"
compose() { docker compose --env-file "$ROOT/.bootstrap/observability/grafana.env" -f "$ROOT/observability/compose.yaml" "$@"; }
compose exec -T postgres psql -v ON_ERROR_STOP=1 -U mocknet -d mocknet < "$ROOT/observability/postgres/support-views.sql"
# Passwords are generated hex, passed over stdin, never interpolated into process arguments.
{
  printf "SELECT 'CREATE ROLE grafana_reader LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='grafana_reader')\\gexec\n"
  printf "ALTER ROLE grafana_reader PASSWORD '%s';\n" "$MOCKNET_READER_PASSWORD"
  printf "ALTER ROLE grafana_reader SET default_transaction_read_only=on;\nALTER ROLE grafana_reader SET statement_timeout='5s';\n"
  printf 'GRANT CONNECT ON DATABASE mocknet TO grafana_reader;\nGRANT USAGE ON SCHEMA support TO grafana_reader;\nGRANT SELECT ON ALL TABLES IN SCHEMA support TO grafana_reader;\n'
} | compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U mocknet -d mocknet
