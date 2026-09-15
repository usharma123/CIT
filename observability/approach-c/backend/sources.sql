-- Local substitutes for the diagram's database and ODS sources. No corporate mapping is implied.
CREATE SCHEMA IF NOT EXISTS c_support;
CREATE OR REPLACE VIEW c_support.database_summary AS
SELECT 'trade'::text AS category, status::text AS item, count(*)::bigint AS value
FROM public.trades GROUP BY status
UNION ALL
SELECT 'queue', queue_name || '/' || status, count(*)::bigint
FROM public.queue_messages GROUP BY queue_name,status;
GRANT USAGE ON SCHEMA c_support TO c_source_reader;
GRANT SELECT ON c_support.database_summary TO c_source_reader;
