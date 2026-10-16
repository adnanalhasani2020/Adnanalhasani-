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


## Follow-up — EXEC-0046

Database migration `0024_linked_financial_currency_consistency.sql` requires linked Loans/Obligations, Payments/Obligations, and Payments/Settlements to use the same currency. Updates that would invalidate linked records are rejected. This is intentionally a no-conversion contract while exchange-rate behavior remains unapproved. Tests and latest-head CI are pending.


## Follow-up — EXEC-0047

Migration `0025_settlement_recognition_currency_consistency.sql` ensures a recognized Financial Transaction linked to a Settlement uses the Payment's currency. This closes the final currency link from the source payment through settlement recognition. The regression test rejects a mismatched currency and confirms the persisted transaction remains unchanged. Latest-head CI verification is pending.


## Follow-up — EXEC-0048

The application read paths now fail closed when persisted currency codes disagree across linked Loans/Obligations, Obligations/Payments, or Settlements and their linked financial records. This detects inconsistent legacy or externally corrupted data even if older database rows predate the new write guards. Reads do not silently repair or mutate the inconsistent records. Regression tests simulate legacy mismatches and assert the stored values remain unchanged.
