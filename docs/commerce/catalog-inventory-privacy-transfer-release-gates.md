# Catalog, inventory costing, phone authentication, and authorization release gates

## Implemented in the repository (not yet proven deployed)
- Catalog discovery pagination, idempotent unpriced linking, organization/activity mapping, and onboarding handoff.
- Selected items can be configured to an existing activity, branch, warehouse and curated base/sale units.
- A catalog-backed inventory creation RPC creates the priced local offering, opening stock movement, activity mapping, branch/warehouse/unit setup, and explicit global-catalog offering mapping in one transaction. It uses an idempotency key and rejects conflicting retries.
- Nearby popularity uses completed invoice transaction counts, not incomparable raw quantities across units. Price suggestions aggregate branch-level merchant medians into market quartiles. Results use explicit offering-to-global-item mappings and cohort aggregates; seller identities and individual merchant prices are not returned.
- Seller-invoice transfer requests require recipient approval; approval creates a draft purchase order with an active receiving location. Stock changes only through the normal receipt workflow.
- The repository contains additive FIFO/LIFO/weighted-average cost-layer migrations, checkout allocation integration, return/cancellation restoration, and an audit view. This is a code-level implementation only; it is not production-ready until all migrations are applied in order and database integration tests reconcile inventory asset and COGS.
- Activity profile supports Supabase phone SMS OTP request and verification for existing accounts. OTP requires the Supabase Auth SMS provider to be enabled and configured.

## Known high-priority gaps
1. **Warehouse isolation:** the existing inventory balance and checkout model is location-scoped, not warehouse-scoped. Setup records the selected warehouse, but balances and sale movements still use the branch/location key. Independent warehouse balances require a coordinated migration of balance identity, receipt/issue/return movements, checkout, transfers, and reports. Do not describe this as complete.
2. **SIM/device verification:** SMS OTP proves possession at the time of the challenge only. Android cannot reliably prove the same SIM remains installed every day without carrier/platform support. Do not block access based on an unreliable SIM-detection claim. A safer control is server-side device/session registration with revocation, risk-based reauthentication, and explicit policy configuration.
3. **WhatsApp and voice OTP:** not enabled by the current UI. They require provider configuration and verified delivery tests.
4. **Authorization completeness:** catalog and core purchase/checkout RPCs have server-side membership checks, but an exhaustive role-by-screen/action audit still requires enumerating all SECURITY DEFINER functions, grants, RLS policies and tenant/branch paths, then testing every role against a staging database.
5. **Production rollout:** repository migrations must be reconciled against the actual production migration history before any deployment. No live migration should be applied until a representative staging environment has been restored and the full migration chain, two-tenant isolation, role matrix and financial reconciliation tests pass.

## Costing behavior and controls
- The repository now contains a cost-layer implementation for FIFO, LIFO and weighted average. The older balance-cost path remains the fallback when no costing policy is configured.
- Existing positive balances with known unit cost are seeded as opening layers; historical receipt layers are not fabricated.
- New receipts create layers; checkout allocation is intended to consume layers and snapshot cost; returns/cancellations restore cost provenance.
- Never enable a costing policy until the deployed database has the complete migration chain and tests prove no negative layer quantities, idempotent allocation, correct return restoration, and balanced inventory-asset/COGS journals.
- LIFO policy suitability must be reviewed for the applicable accounting jurisdiction before enabling it.

## Release gates
- Verify repository migration order against the target project's migration history.
- Apply to a representative staging database first.
- Run database integration tests and Android build/tests on the exact candidate commit.
- Test at least two organizations and owner/admin/manager/staff/read-only roles, including direct RPC and direct REST attempts.
- Verify no cross-tenant catalog, price, invoice, inventory, notification or audit leakage.
- Reconcile opening stock, receipts, sales, returns, cancellations, cost layers, and general-ledger postings.
- Run Supabase security and performance advisors after migrations.
- Do not merge/deploy a candidate with failed or stale CI results.
