# EXEC-0044 — Currency-code representation integrity

**Status:** Implemented on an isolated branch; verification is pending.

## Findings

The repository has no currency master-data or exchange-rate domain. It stores currency codes on financial records, but the representation contract was inconsistent:
- Payment creation accepted arbitrary non-empty text.
- Obligation creation accepted arbitrary non-empty text.
- Purchases, supplier invoices, opening balances, and balance reads used stricter uppercase checks.
- SQLite did not independently enforce canonical representation on loans, obligations, payments, financial transactions, or ledger entries.

## Changes

- Added shared helpers for normalized input codes and strict canonical-code validation.
- Payment, Obligation, and Loan creation now trim surrounding whitespace, require exactly three ASCII letters, and persist uppercase codes.
- Payment, Obligation, and Loan restore/read paths fail closed if persisted currency codes are malformed or noncanonical.
- Linked Payment/Obligation currency comparison uses canonical representations.
- Added migration `0022_currency_code_representation_guards.sql` with insert/update triggers on existing currency-bearing financial tables to reject noncanonical direct SQL writes.
- Added regression coverage for malformed codes and normalization.

## Explicit boundaries

These checks validate representation only. They do not prove that a code is officially assigned or supported, define a currency's minor-unit precision, set a default currency for an account or organization, define target markets, or implement exchange rates/conversion. Those are product-policy decisions under **Q-0023**, still marked Critical Decision. No new currency module, exchange-rate behavior, or financial posting semantics are introduced.

## Verification

GitHub Actions and local test execution have not yet been run for this branch. Do not merge until tests pass on the exact PR head.


## Follow-up — EXEC-0045

The database also enforces that Ledger Entry account/currency matches its parent Financial Transaction. This prevents incorrect currency balances from being persisted through direct SQL writes or later transaction edits. Regression tests cover mismatched insert/update paths. Verification is pending for this follow-up.
