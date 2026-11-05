# Production verification snapshot — 2026-11-06

## Scope
Read-only verification of the connected Supabase production project and repository `main` during the storage-location completion work. No production data, schema, migration history, or project settings were changed.

## Production migration and schema state
- Applied migration count: **216**.
- Latest applied migration: `20261105192134_backfill_missing_base_sell_units`.
- Present in production: `commercial_warehouses`, `commercial_storage_locations`, `commercial_inventory_balances`, `commercial_stock_movements`, `commercial_inventory_receipts`, and `commercial_inventory_cost_layers`.
- Not present in production: `commercial_inventory_storage_balances` and `commercial_inventory_storage_movements`.
- The repository's storage-location allocation/transfer migration is therefore not live. Android must not expose working allocation/transfer writes until the migration chain is validated and deliberately deployed.

## Security advisor snapshot
Observed 2026-11-05:
- 86 public `SECURITY DEFINER` functions are executable by `authenticated`. This is a warning requiring function-by-function review of grants and internal authorization; it is **not proof that all 86 are exploitable**. Do not blanket-revoke these grants.
- 3 tables have RLS enabled but no direct policies: `commercial_account_delegations`, `commercial_catalog_inventory_creation_requests`, and `commercial_personal_account_organizations`. No policy means direct client table access is denied; verify intended access is provided only through reviewed RPCs.
- Leaked-password protection is disabled in Supabase Auth. Enablement is an account/project security setting and must be reviewed before production rollout.

## Performance advisor snapshot
Observed 2026-11-05:
- 62 foreign keys without covering indexes.
- 1 RLS init-plan warning.
- 9 multiple-permissive-policy warnings.
- 3 duplicate-index warnings.
- 241 unused-index notices. These are observations, not instructions to mass-delete indexes; review query plans and workload before any index change.

## Storage-location release status
- The repository contains an additive migration for storage-location balances and append-only allocation/transfer movements.
- The work branch adds an Android screen, precise decimal availability reading, branch-scoped warehouse selection, persisted idempotency keys, and a reservation-aware allocation guard.
- This remains an optional sub-ledger. It does not yet post sales, receipts, returns, or cost allocations by storage location and does not replace the branch-level stock balance.
- The screen is gated when the storage schema or RPCs are unavailable.
- A full migration replay and authenticated SQL integration tests are still required. The repository now has a disposable local Supabase replay workflow on `main` (merged through PR #760); this PR's next synchronization will exercise it against the full migration history and pgTAP suite. Do not apply these migrations to production as part of this development pass.

## Required next actions
1. Review the 86 callable SECURITY DEFINER functions in groups, preserving intentional RPC entry points while proving tenant/role checks in each body.
2. Review the three no-policy tables and confirm their intended RPC-only access model.
3. Enable leaked-password protection after confirming the account's operational policy.
4. Replay the complete migration history in a disposable local PostgreSQL/Supabase environment.
5. Complete the stocktake draft/approval/post/reversal workflow and integrate storage-location balances with the primary stock movement and cost ledgers before treating bins as authoritative.
6. Validate the exact candidate commit with Python, Stage 7, Android build, and database tests.
