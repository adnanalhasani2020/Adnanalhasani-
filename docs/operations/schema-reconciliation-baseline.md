# Schema reconciliation baseline — Ruh

**Generated:** 2026-10-23  
**Purpose:** Reconcile the SQLite application schema and live Supabase schema without discarding fields or silently overwriting business data.

## What was inspected

- Live Supabase project: `dukeigbrzvpxzkbmdbeh`, schema `public`.
- SQLite repository baseline: `schema/migrations/0001_initial_relational_schema.sql` plus the repository's additive migrations.
- Application persistence entry point: `src/agent_core/persistence.py`.
- Commercial web application: `src/agent_core/commercial_web.py`.

## Baseline observations

- Supabase currently exposes **56 public tables**. The reviewed table inventory shows RLS enabled on all of them.
- The initial SQLite relational migration contains **47 domain tables** plus the `schema_migrations` ledger. Later SQLite migrations add more tables and columns; therefore 47 is not the full current SQLite table count.
- The schemas are not simple copies of one another. SQLite includes generic/domain tables such as `persons`, `activities`, `products`, `offerings`, `sales`, `payments`, `financial_accounts`, `clinical_records`, `pending_operations`, and `audit_records`. Supabase includes commercial-specific tables such as `commercial_organizations`, `commercial_organization_members`, `commercial_sales`, `commercial_sales_returns`, `commercial_global_units`, `commercial_supported_locales`, and `commercial_journal_entries`.
- Some names that look similar are not proven semantic equivalents. For example, generic SQLite `sales` and Supabase `commercial_sales` have different identifiers, authentication references, currency/amount models, and operational fields. A blind column copy or rename would not safely merge them.
- SQLite migration application is lexical and local. It does not currently apply Supabase migrations or synchronize data.
- A live schema inventory is not a data backup. Do not copy or overwrite business rows merely because a table or column is missing.

## Reconciliation policy

The goal is **union of useful capabilities**, not declaring one database universally authoritative.

1. **Additive-first:** preserve all existing tables and fields. New columns must be nullable or have a safe deterministic default unless a reviewed backfill is supplied.
2. **Explicit semantic mapping:** only merge table/column pairs after documenting identity keys, data types, lifecycle/status semantics, tenant scope, and foreign-key meaning.
3. **Preserve provenance:** synchronized rows must retain source system, source record ID, source version or updated timestamp, and sync operation ID. Never infer identity from a display name.
4. **Conflict, do not silently choose:** if both systems changed the same logical record since the last sync, record a conflict with both versions and require a deterministic domain-specific resolution.
5. **No destructive schema sync:** never drop columns/tables, narrow types, replace primary keys, or rewrite financial/clinical/audit history automatically.
6. **Security parity:** any new Supabase table must have RLS enabled before exposure. Policies must be based on authenticated user and verified organization membership; enabling RLS without a correct policy is fail-closed but not functional integration.
7. **Transactional domains:** sales, inventory, returns, payments, journal entries, and clinical records cannot be merged by generic last-write-wins. Use domain-specific RPCs and idempotency.
8. **Reference catalogs:** shared catalog/reference data can be imported additively with source/version metadata and unique natural keys where those keys are verified.
9. **Schema drift output:** report missing tables, missing columns, incompatible types, and policy/constraint differences before generating DDL. Review generated DDL before applying it to production.
10. **Backups and rollback:** take verified backups of all SQLite files and a Supabase database backup/export before data migration. `APP_DB_PATH` selects a SQLite file; it does not merge existing database files.

## Implementation sequence

### Phase A — Inventory and mapping
- Capture every current SQLite table and column from the actual configured database, not only migration 0001.
- Capture the current live Supabase table/column/constraint/RLS inventory.
- Produce a mapping registry with statuses: `equivalent`, `partial_overlap`, `sqlite_only`, `supabase_only`, or `unresolved`.
- Compare indexes, unique constraints, foreign keys, checks, triggers, and RLS policies.

### Phase B — Additive schema alignment
- For confirmed equivalents, add missing nullable/defaulted columns and compatible indexes in both stores.
- For confirmed missing tables, create compatible tables with explicit primary keys, foreign keys, constraints, and RLS policy.
- Keep system-specific IDs and store a stable cross-system mapping instead of forcing UUID and TEXT IDs to be equal.
- Test migrations on disposable copies before applying to live data.

### Phase C — Data exchange
- Import non-sensitive reference data additively first.
- Add idempotent change/outbox records and sync checkpoints.
- Reconcile conflicts and retry interrupted operations safely.
- Add role/tenant isolation tests, then enable user-facing reads/writes for the synchronized domain.

## Current release boundary

No automatic table/column cloning or bulk data copy has been run against the live databases as part of this inventory. The live Supabase project has business-sensitive, financial, identity, audit, and potentially health-related tables; schema shape alone is insufficient to decide that a column or table can be safely copied. The next implementation step is to generate a machine-readable full drift report from the actual SQLite database path and the live Supabase metadata, then apply reviewed additive migrations in small batches.


## Live metadata capture (2026-10-23)

A machine-readable Supabase public-schema snapshot has now been committed at
`schema/reconciliation/supabase-public-schema-snapshot.json`. It contains 56
public tables and 644 columns and can be consumed by the schema reconciliation
utility. A separate security/constraint baseline is recorded in
`schema/reconciliation/supabase-security-constraints-baseline.json`:
961 table constraints, 67 public policies, no public tables reported without
RLS, and no public tables reported without a policy.

These checks establish coverage only, not policy correctness. The actual local
SQLite file selected by `APP_DB_PATH` / `COMMERCIAL_DB_PATH` still must be
inspected in the deployment environment before claiming a complete live
cross-database drift report. Candidate pairs with different names are now
compared by the utility when `--mapping schema/reconciliation/table-mapping.json`
is supplied; the report does not assume that the candidates are equivalent.

