# High-priority completion audit — 2026-11-01

## Scope and evidence
Repository: `adnanalhasani2020/Adnanalhasani-`, branch `main` at commit `5038ed458c6141b38747d4f4bf59f78c763b1b7c`.
Production Supabase project: `dukeigbrzvpxzkbmdbeh` (ACTIVE_HEALTHY, PostgreSQL 17.11.0.003).
A second project, `ygtagdhjlkpounmujolh`, has no migrations or public tables and is not a representative staging database.

## Critical deployment drift
- Production migration history contains 171 migrations; the latest applied version is `20261027195426_guard_return_adjustment_currency`.
- The repository contains 18 additional migrations dated 2026-10-29 through 2026-10-31 for catalog discovery/linking/activity mapping, inventory setup, invoice-to-purchase approval and line mapping, nearby privacy, warehouse context, RLS/index tuning, FIFO/LIFO/weighted-average cost layers, checkout allocation, return/cancel reversals, and cost audit reporting.
- These migrations are present in Git but are not recorded as applied in production.
- Do not apply them directly to production as a batch. The empty second project is not a valid staging target because it has no baseline schema or migration history.

## Production schema snapshot
- 88 public tables were returned by the schema inventory.
- Every listed public table has RLS enabled.
- Security advisor: 45 findings for SECURITY DEFINER functions executable by authenticated users, plus 1 warning that leaked-password protection is disabled. Each function requires intent/grant/body review; the warning is not proof that every function is exploitable.
- Performance advisor: 35 unindexed foreign keys, 12 RLS init-plan warnings, 8 multiple-permissive-policy warnings, 3 duplicate-index warnings, and 206 unused-index notices. Unused-index notices are informational and must not be mass-deleted.
- The 45 SECURITY DEFINER findings need a function-by-function authorization review, not blanket revocation: some RPCs are intentionally callable and enforce authorization inside the function.

## High-priority status
| Workstream | Repository status | Production status | Release blocker |
|---|---|---|---|
| Activity/sector/level catalog discovery | Implemented with pagination RPC and Android UI | New migrations unapplied | Yes |
| Organization/activity item mapping | Implemented with explicit mapping table/RPC | New migrations unapplied | Yes |
| Unpriced linking and unit selection | Implemented with idempotency and authoritative unit lookup | New migrations unapplied | Yes |
| Nearby discovery and expanding radius | UI pagination and nearby RPC exist; privacy migration exists | New migrations unapplied | Yes |
| Market price suggestions | Merchant-level aggregation and quartiles exist | New migrations unapplied | Yes |
| Invoice transfer to purchase | Recipient approval creates draft PO; no stock receipt implicit | New migrations unapplied | Yes |
| FIFO/LIFO/weighted-average costing | Cost layers, checkout integration, reversals and audit view exist in repository | New migrations unapplied | Yes |
| Warehouse-specific balances | Not implemented; inventory remains branch/location scoped | Not implemented | Yes |
| Exhaustive roles/screens/actions matrix | Partial server-side checks; no complete live role matrix | Not verified | Yes |
| SMS OTP | Request/verify UI exists | Provider configuration not verified | Yes |
| Daily same-SIM check | Not technically guaranteed by current Android implementation | Not implemented | Must replace with secure device/session policy |
| WhatsApp/voice OTP | Not enabled | Not configured/verified | Medium |

## Safe next execution sequence
1. Provision a representative staging project from the complete repository migration baseline (not the empty invoice-validation project), with isolated test data and no production secrets.
2. Reconcile the full migration chain and test the 18 pending migrations in chronological order.
3. Fix migration errors and any schema drift using additive, reversible changes where possible.
4. Run database integration tests for catalog onboarding, nearby paging/no-duplicates, transfer approval, receipt, costing allocation, return/cancellation, and journal reconciliation.
5. Implement warehouse-level inventory identity end-to-end; do not merely add a warehouse column to the UI.
6. Complete a role × screen × action matrix and test direct RPC/REST bypass attempts across two organizations.
7. Configure and verify Auth SMS delivery; replace daily SIM detection with device/session registration and revocation controls.
8. Run security/performance advisors and Android build/tests on the exact candidate commit.
9. Only after all gates pass, open a production rollout with backup, migration-by-migration verification, and rollback/forward-fix plan.

## Actions taken in this audit
- Created branch `codex/high-priority-commerce-completion` from `main`.
- No production data or schema was changed.
- No migrations were applied to either Supabase project.
- No feature is marked production-complete solely because its source code exists.


## Post-audit deployment update — 2026-11-02

The table above is a historical snapshot from the audit commit, not the current production state. A subsequent production verification after the Nov 2 migration batch found:

- Production migration history now extends through `20261102002527`; the later catalog, activity mapping, purchase approval, warehouse context projection, inventory costing, invoice notifications, and multi-service booking migrations are recorded as applied.
- The public schema now contains **102 base tables** (up from the 88-table snapshot above); every table returned by the latest table inventory has RLS enabled.
- The 12 RPCs previously marked unavailable in the reconciliation audit are now all present in production (**12/12**).
- Critical checkout and customer-payment RPC privileges were checked: no `anon` EXECUTE; authenticated access is granted to the intended entry points.
- PR #672 was merged to `main` at `fe9194a8beff71b2a4e050ad9e7562001f94b33e`, repairing two SQL migrations and shortening the delegated multi-service booking RPC identifier to PostgreSQL's supported length.

### Still open after the deployment

- Warehouse-specific stock balances and storage-location posting are not yet a complete end-to-end workflow. Existing warehouse/location tables and a context projection do not prove stock is posted to warehouse-level balances.
- A complete role × screen × action authorization matrix remains unverified. Do not blanket-revoke authenticated execution from SECURITY DEFINER RPCs; review each function's internal authorization and intended caller.
- SMS provider delivery, secure device/session registration, and replacement of daily same-SIM checks remain unverified or unimplemented.
- Full authenticated Android end-to-end tests and a successful build on the current commit have not been evidenced. No paid Supabase branch/project was created, and no valid free staging baseline was available.

This update does not retroactively change the historical audit's scope or actions. It supersedes only the deployment-status statements above where the live verification now differs.
