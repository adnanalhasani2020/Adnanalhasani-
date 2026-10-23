# Bidirectional schema drift report

The reconciliation utility compares SQLite and Supabase without changing either database. For tables with different names, pass the candidate mapping registry as well.

## 1. Export the live Supabase schema

Run `schema/reconciliation/supabase-schema-snapshot.sql` in the Supabase SQL editor. Save the returned `snapshot` JSON object to a local file named `supabase-schema.json`. This is metadata only, not a data backup.

## 2. Run against the actual SQLite database

From the repository root:

```bash
python -m agent_core.schema_reconciliation \
  --sqlite /path/to/the/actual/application.sqlite3 \
  --supabase-snapshot /path/to/supabase-schema.json \
  --mapping schema/reconciliation/table-mapping.json \
  --output schema/reconciliation/drift-report.json
```

Ensure the project's source directory is on `PYTHONPATH` if the package is not installed, for example `PYTHONPATH=src python -m agent_core.schema_reconciliation ...`.

The report contains:
- tables present only in one store;
- columns present only in one store for identically named tables;
- type differences;
- field-level differences for explicitly mapped differently named candidate tables.

Mapping entries are hypotheses, not proof of equivalence. Review primary keys, foreign keys, tenant ownership, nullability, defaults, indexes, checks, RLS policies, and business semantics before creating migrations. The tool never emits DDL or copies rows. Do not apply generic changes to identity, finance, clinical, audit, payment, or inventory tables based on column names alone.
