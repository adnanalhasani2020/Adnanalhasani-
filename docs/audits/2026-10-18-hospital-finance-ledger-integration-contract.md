# Hospital billing to Finance Ledger: integration gap and safe contract

**Status:** design/audit only; no financial or hospital runtime behavior is changed by this document.  
**Reviewed:** 2026-10-18  
**Scope:** `hospital/core.py`, `src/agent_core/domain_finance.py`, `src/agent_core/financial_orchestration.py`, `src/agent_core/integrity.py`, and the existing Stage 12A finance traceability record.

## Finding

Hospital billing and Finance Ledger are separate bounded contexts today. A hospital invoice/payment is **not** proven to be recognized in the authoritative Finance Ledger.

### Hospital-side facts

- `hospital/core.py` owns SQLite tables `invoices` and `payments`.
- An invoice stores an integer `total_minor`, currency (default `YER`), patient ID, optional encounter ID, status, and creation time.
- `record_payment` accepts a positive amount and method, rejects void/missing invoices and overpayment, calculates prior paid amounts, inserts a payment, updates invoice status, and writes an append-only audit event.
- The hospital payment row has no enforced unique idempotency key or durable link to a Finance obligation, payment, settlement, transaction, or ledger-entry ID. Its `reference` field is optional and not unique.
- The hospital operation uses a transaction in its own SQLite database; that transaction cannot atomically commit with the Finance operation store/ledger.

### Finance-side facts

- `FinanceRecognitionCommand` is the sole domain authority for creating a `FinancialTransaction` and `LedgerEntry`.
- `FinancialOrchestrator.recognize` requires matching Finance `Payment`, `Settlement`, and `Obligation`, an active `FinancialAccount`, authorization/approval inputs, connectivity, and durable operation identity.
- Finance currently rejects partial settlement unless remaining obligation amounts are tracked. Hospital billing explicitly supports partial payments. Passing a hospital partial payment directly to this orchestration would therefore be incorrect.
- Finance identifiers are UUID-based; hospital invoice/payment identifiers are SQLite integer IDs. No verified identity mapping or currency/ownership mapping between the two models was found in the reviewed paths.

## Safety conclusion

Do not create a ledger entry directly from a hospital invoice or payment, and do not equate a receipt/reference string with a durable idempotency key. Doing either could create duplicate or semantically incorrect financial recognition. Do not mark a hospital invoice paid merely because a Finance call was attempted.

## Required integration contract before implementation

1. **Approved accounting semantics:** specify whether the event is invoice issuance (receivable recognition), cash receipt, or both; define accounts, sign conventions, tax/discount/refund/void treatment, and the meaning of each ledger entry.
2. **Currency and ownership mapping:** resolve hospital patient identity to the authorized Finance party/account and verify currency compatibility. Never guess a Finance account from a patient integer ID.
3. **Partial-payment policy:** either extend Finance to track remaining obligation balances with migration and regression coverage, or explicitly defer Finance recognition until the supported full-settlement condition is met. Do not silently aggregate or discard payment events.
4. **Stable cross-system identity:** persist a durable mapping keyed by hospital source entity and event (for example, hospital payment ID + event version), with a uniqueness constraint and stored Finance workflow/transaction/ledger identifiers. Define conflict behavior for same key/different payload.
5. **Failure/replay/reconciliation:** use an outbox or equivalent durable delivery record; retry with the same operation identity; make replays return the recorded result; record permanent failures for reconciliation. Because the two SQLite resources cannot share one transaction, design for partial failure explicitly.
6. **Corrections:** never edit or delete recognized ledger history. Define approved reversal/correction flows and reconcile the hospital invoice/payment status with their Finance outcomes.
7. **Data-preserving migration:** additive migration only; preserve all existing invoice/payment IDs, references, balances, and audit history. Test both a fresh database and a copy of a populated legacy database.
8. **Verification:** add tests for duplicate delivery, same key/different amount, partial payment, overpayment, currency/account mismatch, crash/retry, reversal, concurrent requests, and reconciliation. Run required GitHub Actions on the exact PR head before merge.

## Suggested implementation sequence

- First agree on accounting policy and partial-payment behavior.
- Then add a narrowly scoped, additive hospital-to-Finance mapping/outbox schema and read-only reconciliation report.
- Only after those contracts are approved, implement a worker/adapter that invokes Finance's existing authorized orchestration boundary. Keep the adapter out of the clinical workflow and do not bypass `FinanceRecognitionCommand`.
- Roll out behind an explicit disabled-by-default feature flag; validate with synthetic data and reconciliation before enabling it.

## Explicit non-claims

This document does not claim that hospital billing is integrated with the Finance Ledger, that the system is production-ready, or that any ledger postings have been created. It records the verified boundary and the prerequisites for a safe implementation.
