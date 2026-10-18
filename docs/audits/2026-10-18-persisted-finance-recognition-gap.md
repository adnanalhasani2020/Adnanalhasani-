# Verified gap: durable Finance recognition boundary

**Reviewed:** 2026-10-18  
**Scope:** source-level verification of Finance persistence and hospital billing integration.  
**Change type:** documentation only; no runtime code, schema, or live data changed.

## Finding

The repository has persisted application services for Financial Accounts, Obligations, Payments, invoices, and supplier invoice evidence. However, the reviewed `FinancialOrchestrator` is not a durable end-to-end settlement-to-ledger application service.

Its `recognize()` path:
- accepts in-memory `Payment`, `Settlement`, `Obligation`, and `FinancialAccount` domain objects;
- validates the matching/authorization/approval contract and rejects partial settlement;
- invokes `FinanceRecognitionCommand`, which constructs `FinancialTransaction` and `LedgerEntry` domain objects;
- updates the obligation object and stores workflow results in an in-memory `_workflows` dictionary;
- uses `DurableOperationAuthority` to persist operation identity and replay outcomes.

The reviewed path does **not** itself persist the resulting `FinancialTransaction`, `LedgerEntry`, `Settlement`, workflow history, or the obligation transition through the canonical relational Finance application boundary. A durable operation record containing IDs/outcome is not equivalent to durable accounting rows. On restart, an in-memory recognition result is not a source of truth for the posted ledger.

A repository-wide code search for inserts into `financial_transactions` and `ledger_entries` found the opening-balance implementation as the application path writing those rows; it did not reveal a general settlement-recognition persistence application service. Inserts/references to `settlements` occur in lifecycle/application modules, but that alone does not close the missing persisted recognition path.

## Evidence reviewed

- `src/agent_core/financial_orchestration.py`: recognition constructs domain objects and keeps workflow results in `self._workflows`; it calls `DurableOperationAuthority.execute` but has no database connection or persistence adapter.
- `src/agent_core/domain_finance.py`: `FinanceRecognitionCommand.recognize()` returns a transaction and ledger-entry pair; it does not persist either object.
- `src/agent_core/integrity.py`: `DurableOperationAuthority` stores operation identity/fingerprint and replay outcome. This protects retries at its own boundary but does not atomically commit the Finance ledger rows.
- `src/agent_core/payment_application.py`: explicitly owns Payment lifecycle only and does not create Settlement, FinancialTransaction, LedgerEntry, or Balance effects.
- `src/agent_core/obligation_application.py`: explicitly owns Obligation lifecycle/history and does not infer satisfaction from Payment.
- `src/agent_core/financial_opening_balance_application.py` and migration `0008_financial_opening_balance.sql`: demonstrate a persisted Finance recognition path for opening balances, not a generic settlement pipeline.
- `schema/migrations/0001_initial_relational_schema.sql`: defines persisted tables and uniqueness/source-reference constraints for Finance records.
- `hospital/core.py`: hospital invoices and payments are persisted separately in `HospitalDB`; payment writes are committed in that database, not in the Finance database.
- `tests/test_stage12a_financial_orchestration.py`: tests the domain orchestration path and derived balance using constructed domain objects; this is not evidence of durable end-to-end ledger persistence.

## Impact on hospital integration

Do not connect hospital invoice/payment events to `FinancialOrchestrator.recognize()` as if it were a persisted ledger API. That could return transaction/entry IDs without corresponding durable accounting rows, and the hospital and Finance commits cannot be made atomic by the current two-database arrangement.

Do not work around this by inserting directly into `financial_transactions` or `ledger_entries` from hospital code, by calling `FinanceRecognitionCommand` as a shortcut, or by treating Payment/Settlement status as posted financial truth.

## Safe next implementation milestone

Before adding the hospital adapter, build and test a Finance-owned persistence application boundary that, in a single Finance database transaction:
1. reloads and validates persisted Payment, Settlement, Obligation, Financial Account, authorization grant, AgentAction, and approval;
2. validates source links, currency, amount, and remaining obligation balance;
3. creates recognized FinancialTransaction and LedgerEntry records using the existing Finance recognition authority and the canonical schema constraints;
4. transitions/records the applicable settlement and obligation state with append-only history;
5. stores a unique durable operation result with replay/conflict semantics in a way that is transactionally consistent with the Finance rows;
6. supports safe reversal/correction through new records, not destructive edits.

Then add the hospital outbox/source-mapping adapter as a separate step. Its hospital-side outbox must be committed in the same HospitalDB transaction as the invoice/payment event; delivery must be retryable and reconciled against a stable Finance operation ID. Since the databases are separate, design for at-least-once delivery plus idempotent Finance handling, not a claimed cross-database atomic transaction.

## Required tests before enabling integration

- fresh database and populated database migrations;
- full settlement and multiple partial payments with remaining balance;
- duplicate delivery and same-key/different-payload conflict;
- process crash between hospital outbox commit, Finance commit, and acknowledgement;
- missing/inactive authorization, mismatched person/account/currency/source references;
- void, refund, reversal, and correction without deleting recognized history;
- durable read-after-restart proving transaction and ledger rows exist and balance reads include them;
- reconciliation for stuck, duplicate, and unmapped source events.

## Non-claims

This is a source-level finding, not a local test run or production certification. No runtime code or database was changed. The accounting policy for hospital receivables, account mapping, refunds, and partial settlement still requires approval.