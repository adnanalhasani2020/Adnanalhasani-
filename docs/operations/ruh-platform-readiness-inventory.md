# Ruh Platform Readiness Inventory

Last checked: 2026-10-23
Scope: live Supabase project `dukeigbrzvpxzkbmdbeh` and repository evidence. This is an evidence-based working inventory, not a claim that the whole product is production-ready.

## Verified live database baseline

- 56 public tables.
- RLS enabled on all 56 tables.
- 0 public tables without any RLS policy.
- 0 SECURITY DEFINER functions executable by `anon` (13 SECURITY DEFINER functions found after adding organization onboarding).
- 53 non-internal triggers.
- 9 module-catalog records.
- 12 supported locales.
- 22 global units, 20 global catalog items, 24 catalog categories, 24 global chart-account templates.
- 180 global translation rows, all currently marked `official`.
- 0 global geographic locations and 0 geographic location names.
- 0 subscription-plan rows.
- At the time of the baseline count: 0 organization-settings rows and 0 organization-module activation rows; a new onboarding RPC now creates these for newly created organizations.
- 0 organization-managed translation rows.
- 0 activity rows, and the core operational tables inspected (organizations, members, sales, returns, inventory, customers, suppliers, purchase orders, accounts, journal entries/lines, bookings and notifications) were empty at the time of the check.

Empty business tables may be expected before onboarding; they are not automatically defects. Empty reference/configuration tables need an explicit decision about whether they should be seeded or created during onboarding.

## Implemented and evidenced

- Core multi-tenant commercial schema, memberships, catalog, inventory, sales, purchases, accounts, orders/bookings, ledger, documents, notifications and platform payment/subscription structures exist.
- RLS is enabled across the public tables and every table has at least one policy.
- Foreign-key index coverage migrations are registered.
- Journal line integrity and immutability after posting have guardrails.
- Journal-entry and sales-return audit triggers exist; audit events are append-only guarded.
- Sales return request/decision/inspection/restock RPCs exist.
- Return request validates null/empty item arrays, duplicate sale-item IDs, quantity input length and idempotency handling.
- Active personal-account uniqueness is enforced by a partial unique index.
- `commercial_return_rpc_null_input_guards` adds explicit NULL guards for return decisions, inspection condition codes and restock flag.
- `commercial_create_organization` is now deployed as an atomic onboarding RPC. It requires an authenticated user; validates organization name/slug, currency format, timezone, active supported locale, optional country code and module selection; then creates the organization, active owner membership, four baseline settings and selected modules in one transaction. The default module selection comes from catalog records marked enabled by default.
- Live verification confirmed onboarding RPC is SECURITY DEFINER with pinned `search_path`, executable by `authenticated`, and not executable by `anon`.
- Current database inspection found no SECURITY DEFINER function executable by `anon`.

## High-priority gaps / unresolved verification

### P0 — Runtime/application integration
- Repository quickstart documents `applications/commercial-mvp` as a local SQLite application (`commercial-mvp.sqlite3`) with its own migrations.
- No evidence yet proves this application is connected to the live Supabase schema. Treat the Supabase schema and SQLite app as separate until integration is implemented and tested.
- The local MVP quickstart explicitly notes there is no product/price/opening-stock admin UI, no electronic payment gateway, and return/refund/tax/discount/final-invoice workflows are outside that path.

### P1 — Bootstrap/configuration data
- No subscription plans are seeded. Define actual tiers, limits, entitlements, billing cycle, grace/cancellation behavior and payment-provider assumptions before inserting plans; do not invent prices.
- Existing organizations (if any are created later outside the new RPC) need a reviewed backfill for settings/modules; do not blindly insert defaults into production.
- Geographic reference tables are empty. Choose an authoritative import source and schema/licensing approach; do not fabricate location records.
- Only 20 global catalog items and 24 categories exist; assess coverage against launch sectors before expanding.
- 180 global translations are marked official, but coverage across all 12 locales and every entity/field has not yet been measured.
- Global chart of accounts has 24 rows; accounting templates and localization/tax requirements require country-specific review.

### P1 — Tenant access and authorization
- `commercial_sales` and `commercial_sale_items` currently have owner-only read policies based on `user_id = auth.uid()`. Confirm intended organization staff visibility and role boundaries; do not broaden access without role requirements and cross-tenant tests.
- Security advisor reports seven pre-existing authenticated-executable SECURITY DEFINER functions. These are not automatically vulnerabilities; review each function's auth checks, grants and NULL handling.
- Run actual RLS tests with at least two organizations and different roles. Schema inspection alone does not prove isolation.
- The new onboarding RPC has been schema/privilege-verified, but not yet exercised through authenticated integration tests.

### P1 — Automated testing and release confidence
- `supabase/tests/database/commercial_integrity_test.sql` now declares 21 pgTAP assertions. These tests have not been proven to run successfully in CI or against a disposable test database.
- Add executable tests for: tenant isolation, anonymous execution denial, null/invalid RPC inputs, duplicate return items, return quantity ceilings, concurrent idempotency, restock retry behavior, journal balancing, and immutability after posting.
- Do not test by creating financial records in production. Use staging or transaction-wrapped disposable fixtures.

### P2 — Operational readiness
- Confirm backup/restore drill, migration rollback/recovery strategy, monitoring/alerts, incident response, secrets rotation, and retention policies.
- Re-check advisor findings after meaningful schema changes. Unused-index warnings are not grounds for blind deletion, especially while tables are empty.
- Confirm payment webhook signature validation, replay handling, and reconciliation before enabling real billing.
- Confirm document storage buckets/policies and upload/download authorization; table existence alone does not establish storage readiness.
- Confirm currency metadata, minor-unit precision, timezone defaults and localization formatting are consistent between database and application.

## Next execution order

1. Resolve application/database architecture: choose Supabase as runtime store, keep SQLite as separate local MVP, or document a deliberate migration path.
2. Test the new onboarding RPC in a disposable authenticated session, including invalid inputs, slug conflicts, default modules and explicit module selections.
3. Define subscription plans and entitlements from actual business decisions; do not seed guessed prices.
4. Add locale coverage and geography import pipeline with provenance.
5. Review and test sales RLS for organization roles without cross-tenant exposure.
6. Run the 21 pgTAP assertions in a disposable environment and add missing cases.
7. Validate payment, document storage, backup/restore and deployment controls.

## Evidence limits

This inventory records live catalog/table/function metadata and repository documentation. It does not claim the application is deployed, the CI suite passed, real payment processing works, location data has been imported, or all nine modules are end-to-end complete.
