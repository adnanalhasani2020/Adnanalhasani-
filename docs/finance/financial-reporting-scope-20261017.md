# Financial Reporting — Capability and Safety Boundary

**Date:** 2026-10-17  
**Base commit:** `1f772c90688274b8191007434ec640a4339d6db6`  
**Status:** Initial read-only ledger activity report proposed; broader financial statements remain blocked by missing accounting model components.

## Repository findings

The current main branch has a persisted `FinancialBalanceReader` that derives a currency-specific balance from recognized financial transactions and posted ledger entries. It fails closed on relevant orphaned entries, unrecognized transactions, and account/currency mismatches. Finance schema stores signed integer minor-unit amounts, currency codes, transaction recognition metadata, posting timestamps, and ledger sequence.

The repository does not yet establish a complete double-entry accounting model or persisted chart-account mapping for ledger postings. The existing smart chart-of-accounts module is intentionally persistence-free and is not wired into the ledger. Therefore the data currently available does not support a defensible general ledger by chart account, trial balance, income statement, balance sheet, cash-flow statement, tax report, or bank reconciliation.

## Safe first report

The proposed `FinancialLedgerActivityReportReader` is a read-only account-level statement with:
- a required account and one canonical currency per report;
- an explicit half-open period `[start_at, end_at)`;
- opening balance from earlier recognized/posted ledger entries;
- ordered activity lines with transaction/entry references, signed minor-unit amount, recognition source, and timestamps;
- period activity total and closing balance;
- fail-closed behavior when relevant ledger/transaction data is inconsistent;
- no writes, automatic repair, currency conversion, or inferred posting.

Amounts are kept in minor units. Different currencies are never summed. An activity report is not proof of cash movement, bank reconciliation, or a complete financial statement.

## Reports that must not be fabricated

1. **Trial balance:** requires chart-account mapping and explicit debit/credit or equivalent balanced posting semantics.
2. **Income statement:** requires authoritative classification of recognized entries into revenue/expense accounts and a defined reporting-period policy.
3. **Balance sheet:** requires asset/liability/equity account mapping, opening/closing period semantics, and treatment of corrections and retained earnings.
4. **Cash-flow statement:** requires a defined cash/cash-equivalent classification and reliable transaction categories.
5. **Accounts receivable/payable aging:** current obligations and payments do not by themselves establish a complete remaining-balance/partial-settlement model.
6. **Bank reconciliation:** requires imported/confirmed bank or wallet statement lines and matching/reconciliation state; none is established by a posted internal ledger entry.
7. **Multi-currency consolidated reporting:** requires an approved exchange-rate source, rate timestamp/purpose, conversion/rounding policy, and treatment of realized/unrealized differences. Existing FX primitives alone do not make consolidated reports correct.

## Release gates for further reporting work

- Define the authoritative chart-account and posting model; do not treat a Financial Account as a chart-of-accounts node without an explicit approved mapping.
- Specify report formulas, period inclusivity, timezone rules, currency handling, and adjustment/reversal treatment.
- Add independent fixtures with hand-calculated expected totals and boundary cases.
- Test corrupted/orphaned ledger rows, account/currency mismatches, empty periods, offset timestamps, and stable ordering.
- Preserve read-only behavior and ensure a failed report never repairs financial history.
- Run the full test suite and record exact commit and CI evidence before merge.

This document is a technical capability boundary, not a claim of accounting-policy approval or production readiness.
