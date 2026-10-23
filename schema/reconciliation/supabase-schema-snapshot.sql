-- Export the live public-schema shape for schema_reconciliation.py.
-- Run in the Supabase SQL editor and save the single JSON value as supabase-schema.json.
-- This query reads metadata only; it does not modify application data.
SELECT jsonb_build_object(
  'tables',
  COALESCE(
    jsonb_object_agg(
      table_name,
      jsonb_build_object('columns', columns)
      ORDER BY table_name
    ),
    '{}'::jsonb
  )
) AS snapshot
FROM (
  SELECT
    table_name,
    jsonb_object_agg(column_name, data_type ORDER BY ordinal_position) AS columns
  FROM information_schema.columns
  WHERE table_schema = 'public'
  GROUP BY table_name
) AS public_tables;
