#!/usr/bin/env python3
"""Provision the separate C journal and reporting store; never print credentials."""
import pathlib
import psycopg
from psycopg import sql

ROOT = pathlib.Path(__file__).resolve().parents[1]
secret = dict(line.split('=', 1) for line in (ROOT / '.bootstrap/observability/grafana.env').read_text().splitlines())
admin = dict(host='127.0.0.1', port=15452, user='mocknet_c', password=secret['MOCKNET_DB_PASSWORD'])
with psycopg.connect(**admin, dbname='mocknet_c', autocommit=True) as db:
    for role, key in [('c_journal_reader', 'MOCKNET_C_COLLECTOR_PASSWORD'), ('c_reporter', 'MOCKNET_C_COLLECTOR_PASSWORD'), ('grafana_c', 'MOCKNET_READER_PASSWORD'),
                      ('c_source_reader','MOCKNET_C_BACKEND_DB_PASSWORD'), ('c_backend_reader','MOCKNET_C_BACKEND_DB_PASSWORD'), ('c_backend_writer','MOCKNET_C_BACKEND_DB_PASSWORD')]:
        if not db.execute('select 1 from pg_roles where rolname=%s', (role,)).fetchone():
            db.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {}').format(sql.Identifier(role), sql.Literal(secret[key])))
    if not db.execute("select 1 from pg_database where datname='telemetry_c'").fetchone():
        db.execute('CREATE DATABASE telemetry_c OWNER c_reporter')
    db.execute((ROOT / 'observability/approach-c/journal.sql').read_text())
    db.execute((ROOT / 'observability/approach-c/backend/sources.sql').read_text())
    db.execute('GRANT SELECT, UPDATE(delivered_at), DELETE ON c_mq_journal TO c_journal_reader')
    db.execute('REVOKE CONNECT ON DATABASE mocknet_c FROM PUBLIC')
    db.execute('GRANT CONNECT ON DATABASE mocknet_c TO mocknet_c, c_journal_reader, c_source_reader')
with psycopg.connect(**admin, dbname='telemetry_c', autocommit=True) as db:
    db.execute('SET ROLE c_reporter')
    db.execute((ROOT / 'observability/approach-c/reporting.sql').read_text())
    db.execute((ROOT / 'observability/approach-c/waterfall.sql').read_text())
    db.execute((ROOT / 'observability/approach-c/backend/reporting.sql').read_text())
    db.execute('GRANT USAGE ON SCHEMA public TO grafana_c')
    db.execute('GRANT SELECT ON evidence,queue_samples,c_queue,c_attempt,c_call,c_operations,c_evidence_health TO grafana_c')
    db.execute('GRANT SELECT ON c_source_status,c_source_values,tool_runs TO grafana_c')
    db.execute('GRANT USAGE ON SCHEMA public TO c_backend_reader,c_backend_writer')
    db.execute('GRANT SELECT ON c_queue,c_call,c_operations,c_evidence_health,tool_runs TO c_backend_reader')
    db.execute('GRANT INSERT,UPDATE ON tool_runs TO c_backend_writer')
    db.execute('GRANT SELECT(run_id) ON tool_runs TO c_backend_writer')
    db.execute('RESET ROLE')
    db.execute('REVOKE CONNECT ON DATABASE telemetry_c FROM PUBLIC')
    db.execute('GRANT CONNECT ON DATABASE telemetry_c TO c_reporter, grafana_c, mocknet_c,c_backend_reader,c_backend_writer')
    db.execute("ALTER ROLE grafana_c SET default_transaction_read_only=on")
    db.execute("ALTER ROLE grafana_c SET statement_timeout='10s'")
    for role in ('c_source_reader','c_backend_reader'):
        db.execute(sql.SQL('ALTER ROLE {} SET default_transaction_read_only=on').format(sql.Identifier(role)))
    for role in ('c_source_reader','c_backend_reader','c_backend_writer'):
        db.execute(sql.SQL("ALTER ROLE {} SET statement_timeout='3s'").format(sql.Identifier(role)))
print('C journal triggers, reporting views and restricted collector/Grafana roles ready')
