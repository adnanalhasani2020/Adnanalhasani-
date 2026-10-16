# Chart of Accounts — Safe Implementation Plan

**Status:** Implementation gate / work plan  
**Date:** 2026-10-16  
**Target:** `adnanalhasani2020/Adnanalhasani-`  
**Design contract:** [smart-chart-of-accounts.md](smart-chart-of-accounts.md)

## Why this plan exists

The current main-branch capability audit states that the system already has a Financial Account lifecycle, ledger-backed balance reads, a financial orchestration slice (Stage 12A), instruments/vouchers, SQLite migrations, and regression tests. It also explicitly states that Durable Financial Workflow / Stage 12B is deferred and unauthorized.

A chart of accounts must therefore be integrated with the existing ledger/account identity and must not be treated as permission to start Stage 12B, create a second ledger, or change the release scope.

## Branches identified for reconciliation

Before implementation, compare the current `main` with these existing branches and their PRs/commits where available; branch names alone do not prove their contents are merged or safe to reuse:

- `functional-batch14-financial-account-lifecycle`
- `functional-batch13-persisted-financial-balance-read`
- `feature/financial-opening-balances`
- `fix/financial-opening-balance-validation`
- `fix/financial-app-savepoint-boundaries`
- `fix/financial-transition-state-types`
- `docs/voucher-cancellation-contract-20261016`
- `fix/currency-code-integrity-20261016`
- `fix/finance-obligation-recognition-guards`

Do not cherry-pick or merge by branch name alone. Establish each branch's base, current status, changed files, tests, and whether its change is already represented on `main`.

## Gate 0 — source findings confirmed on main

Initial read-only inspection has confirmed these boundaries:

- `src/agent_core/financial_account_application.py` owns a Financial Account record lifecycle (create/restore/close) and explicitly does not recognize transactions or create ledger entries/balances. It uses existing authorization checks, optimistic versioning, history, and caller-aware transaction/savepoint handling.
- `src/agent_core/financial_balance_read.py` derives balances from recognized financial transactions and posted ledger entries; it does not persist balance snapshots. It validates account existence, currency format, timestamps, and fails closed on ledger/transaction account or currency mismatches.
- `src/agent_core/financial_opening_balance_application.py` recognizes opening balances as financial transactions and ledger entries, with authorization, approval, provenance/history, and a unique opening balance per financial account and currency.
- `src/agent_core/instrument_application.py` is internal persistence for instrument lifecycle/usage and explicitly does not mutate financial accounts or ledger records.
- `schema/migrations/0001_initial_relational_schema.sql` is the initial relational schema; `schema/migrations/0008_financial_opening_balance.sql` rebuilds finance transaction/ledger tables and adds a unique index for opening-balance account/currency.
- Relevant existing regression suites include `tests/test_stage29_financial_balance_read.py`, `tests/test_stage30_financial_account_application.py`, `tests/test_stage31_financial_opening_balance.py`, and `tests/test_stage31_instrument_application.py`.

**Important conclusion:** Financial Account is not automatically a chart-of-accounts node. The inspected interfaces do not establish an existing chart hierarchy/code model. The new primitives are intentionally pure and non-persistent; they do not modify financial transactions, ledger entries, account balances, or migrations.

Continue the inventory from the actual `main` source and migrations:

1. Canonical Financial Account model, repository, lifecycle application service, and identifier.
2. Ledger/recognized-entry schema and how balance reads are calculated.
3. Instrument/voucher model, cancellation rules, and relationship to ledger records.
4. Currency definitions and the current currency-code integrity constraint.
5. Opening-balance models and validation rules.
6. SQLite migration registry/order and transaction/savepoint conventions.
7. Authorization, audit, provenance, idempotency, and test-fixture conventions.
8. Any existing account-code, account-name, account-type, or parent relationship.

**Exit criteria:** one written ownership map; no guessed schema names; no duplicate domain owner.

## Gate 1 — define a minimum viable chart without posting

Start with read-only capabilities and domain validation before financial posting:

- A versioned chart template and stable account identity.
- Configurable textual account codes, preserving leading zeroes.
- Account class, optional parent, and group-vs-posting account policy.
- Scope-aware uniqueness and parent validation.
- Deterministic cycle detection and class-compatible hierarchy validation.
- Read/search/list/tree APIs that follow existing pagination and authorization conventions.
- A read-only chart diagnostic report (duplicates, orphans, cycles, invalid hierarchy).
- Arabic/English name normalization for duplicate suggestions, with no automatic merge.
- Change-impact preview before code, parent, class, or status changes.

Do not add mutable balances to account definitions. Do not infer postings from payment or voucher existence.

## Gate 2 — persistence only after the inventory

If a suitable canonical chart/account representation already exists, extend it rather than creating a parallel table. If none exists, record the decision and migration plan before creating schema.

For any schema change:

- Preserve existing IDs and foreign-key relationships.
- Add constraints only after a read-only diagnostic against populated data.
- Use the repository's migration runner and transaction boundaries.
- Make migrations additive/reversible where feasible.
- Avoid destructive startup migrations and implicit data repair.
- Test duplicate-code races and parent changes in a transaction.
- Keep legacy callers compatible or explicitly migrate them with regression coverage.

## Gate 3 — tests and regression

Add tests using the repository's established test style. At minimum:

1. Root/child creation and retrieval.
2. Code uniqueness within scope; same code in separate scopes if supported.
3. Leading-zero code preservation.
4. Missing parent, self-parent, cross-scope parent, and cycle rejection.
5. Incompatible parent/child class rejection.
6. Group accounts cannot be used as posting targets.
7. Historical references prevent physical deletion.
8. Deactivation prevents new use without rewriting history.
9. Arabic normalization proposes duplicates but never merges automatically.
10. Authorization, audit, provenance, idempotency, and concurrent duplicate attempts.
11. Migration over populated databases.
12. Regression of financial-account lifecycle, balance reads, vouchers/cancellation, currency integrity, opening balances, and Stage 12A behavior.

## Gate 4 — release evidence

Before marking the implementation complete, report:

- exact base/head commit SHAs;
- files and migrations changed;
- whether any data migration was executed (must be explicit);
- targeted and full test results;
- CI run URL and result;
- any unresolved risks and feature limits.

Do not call the feature production-ready based only on unit tests.

## Explicit non-goals for this work

- Starting Stage 12B or durable external financial workflows.
- Building a second ledger or independent posting engine.
- Claiming that payments move real money or that vouchers automatically create ledger entries.
- Automatic AI mutation, account merging, or silent financial-history repair.
- Changing release tag `v1.0.0` or strategic priority without an approved decision.

## Immediate next action

The first implementation slice now exists on the feature branch as `src/agent_core/chart_of_accounts.py` with focused tests in `tests/test_chart_of_accounts.py`. It provides deterministic in-memory hierarchy validation, scoped code duplicate detection, leading-zero-safe code suggestions, Arabic/English name normalization, and duplicate candidates that never mutate accounts.

Next, execute the new focused tests and the full existing suite in CI, inspect any failures, then reconcile the listed finance branches. Do not add persistent chart tables or wire chart accounts into the ledger until that evidence and an explicit account-to-ledger mapping contract are reviewed.
