# Production verification snapshot — 2026-11-06

## Scope
Read-only verification of the connected Supabase production project and repository `main` during the storage-location completion work. No production data, schema, migration history, or project settings were changed.

## Production migration and schema state
- Applied migration count: **216**.
- Latest applied migration: `20261105192134_backfill_missing_base_sell_units`.
- Present in production: `commercial_warehouses`, `commercial_storage_locations`, `commercial_inventory_balances`, `commercial_stock_movements`, `commercial_inventory_receipts`, and `commercial_inventory_cost_layers`.
- Not present in production: `commercial_inventory_storage_balances` and `commercial_inventory_storage_movements`.
- Also not present in production: `commercial_inventory_stocktakes`, `commercial_inventory_stocktake_lines`, and `commercial_inventory_stocktake_release_controls`. The new stocktake workflow remains branch-only.
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

## Stocktake and valuation readiness (read-only check)
- The live inventory table currently has one positive balance row without any active cost layer and without a balance unit cost. The warehouse-context view also reports that row as `unconfigured` rather than mapped to a warehouse. The new stocktake posting guard requires exact balance-to-layer quantity reconciliation and a known weighted-average cost; it will reject non-zero variance for this row until an audited opening-cost reconciliation is performed. Warehouse-specific counting also remains blocked until the mapping is resolved.
- The live GL mapping table has no `inventory_gain` or `inventory_shrinkage_expense` mappings. The live schema has no accounting-period/period-close table. The existing GL posting helper does not supply the missing stocktake account mappings or period validation.
- Therefore stocktake posting and reversal are hard-disabled by default in the new migration and in the Android UI. The release-control row is not writable by authenticated users. The local PostgreSQL smoke fixture explicitly opts in only to test inventory/cost mechanics; it does not imply production enablement.
- The work branch now contains an initial branch-level stocktake lifecycle: draft snapshot, count entry, submit, independent approval, idempotent post/reversal RPCs, snapshot revision guards, and FIFO/LIFO/weighted-average cost-layer mechanics. PostgreSQL smoke tests exercise positive/negative variance, reversal, stale snapshots, zero-balance active offerings, reservation protection, and the GL release gate.
- Posting and reversal are still disabled by default in both the database and Android UI because balanced GL journal creation, inventory gain/shrinkage account mappings, and period-close validation are not implemented. The local fixture temporarily enables the gate only to test quantity/cost mechanics.
- The branch-level count snapshot creates zero-balance rows for active offerings with no balance row, so physical stock can be recorded instead of omitted. It remains branch-level and uses the base stock unit; warehouse-specific counts and alternate sell-unit conversion are not yet implemented.

## Storage-location release status
- The repository contains an additive migration for storage-location balances and append-only allocation/transfer movements.
- The work branch adds an Android screen, precise decimal availability reading, branch-scoped warehouse selection, persisted idempotency keys, and a reservation-aware allocation guard.
- This remains an optional sub-ledger. It does not yet post sales, receipts, returns, or cost allocations by storage location and does not replace the branch-level stock balance.
- The screen is gated when the storage schema or RPCs are unavailable.
- The disposable Supabase service startup was repaired by assigning the valid local project id `adnanalhasani-local`; services now start. The next replay exposed the actual historical blocker: migration `20261023200000_commercial_activity_profile_and_hierarchy.sql` alters `public.commercial_organizations`, but that base table is absent in a fresh database. `supabase/manual-migrations/README.md` confirms that the repository's initial commercial schema was provisioned outside the tracked migration history and explicitly calls for a baseline/migration-repair procedure. Therefore a full replay from empty is not currently valid. The misleading full-replay workflow was replaced with a PostgreSQL 17 fixture smoke test that applies only the two new storage migrations and exercises the guarded RPCs; it does not claim to validate the full historical chain. Do not synthesize a production baseline or apply these migrations to production during this development pass.

## Required next actions
1. Review the 86 callable SECURITY DEFINER functions in groups, preserving intentional RPC entry points while proving tenant/role checks in each body.
2. Review the three no-policy tables and confirm their intended RPC-only access model.
3. Enable leaked-password protection after confirming the account's operational policy.
4. Establish a versioned Supabase baseline from the authoritative pre-2026-10-23 schema, then replay the historical migrations in order. The current public-schema snapshot is metadata-only and is not sufficient to recreate constraints, triggers, functions, or policies.
5. Implement balanced GL journal creation, inventory gain/shrinkage account mappings, and accounting-period validation before enabling stocktake posting/reversal; then reconcile the stocktake workflow against production-shaped data.
6. Integrate storage-location balances with receipts, sales, returns, and cost layers before treating bins as authoritative.
7. Validate the exact candidate commit with Python, Stage 7, Android build, and database tests.
