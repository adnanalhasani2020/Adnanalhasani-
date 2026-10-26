# Returns and Payments lifecycle audit

**Reviewed:** 2026-10-19  
**Scope:** Sale return transitions, append-only stock restoration, Invoice links, Payment lifecycle, Settlement capacity, and Finance boundaries.  
**Method:** source and migration review; automated execution is delegated to repository CI after the proposed commit. This is not a production or legal compliance certification.

## Executive summary

The repository has sound foundational controls: checkout is transactionally persisted and idempotent; inventory movements and domain history are append-only; sale and payment transitions require persisted authorization; Payment, Settlement, FinancialTransaction, and LedgerEntry remain distinct. The full returns/payment journey is nevertheless incomplete. Three concrete Payment integrity defects are corrected in this change, with regression tests and database-level protection.

## Findings and disposition

### 1. Settlement-linked payments could be omitted from remaining-obligation capacity
**Severity: high — fixed.** The create-payment capacity check originally reserved capacity using only the direct payments.obligation_id field. The persisted reader already accounted for a Payment linked indirectly through settlements. A Payment with a NULL direct obligation but an existing Settlement could therefore be ignored when another Payment was created against the same Obligation. The new creation check uses the same direct-or-settlement relationship as the persisted reader and rejects over-allocation before writing.

### 2. Invoice/Obligation mismatches could be accepted when the Invoice had no Obligation
**Severity: high — fixed.** The application previously rejected a mismatch only when Invoice.obligation_id was non-NULL. That allowed a Payment to link an Invoice with no Obligation to an unrelated Obligation. The application now requires exact nullable-reference equality; a new migration enforces that invariant for inserts and updates at the SQLite boundary. Existing rows are not rewritten and remain subject to explicit reconciliation.

### 3. Payment creation validated the persisted record after its transaction had committed
**Severity: medium — fixed.** The returned Payment was reloaded only after the atomic transaction completed. If persisted-record validation failed, the caller could receive an exception after the write had already committed. Validation now occurs inside the transaction/savepoint, so an error rolls back the operation owned by the application.

### 4. Partial and multiple return events
**Implemented for fully settled invoice-linked sales, with explicit approval.** The append-only `sale_return_events`/`sale_return_lines` model reserves exact per-line quantities under a stable idempotency key and request fingerprint. A financially linked return first reserves quantities and returns the exact minor-unit refund quote; it does not restock yet. Completion requires a separately persisted, completed refund Payment with provider/external references, an active Finance grant scoped to that return, an approved Finance AgentAction with human approval, an immutable credit note, and a posted negative ledger entry. Only then does the same transaction restore inventory. Credit-note allocation is cumulative per line, so repeated partial returns preserve minor-unit rounding and cannot exceed the original settled Payment. The Sale remains fulfilled during partial returns and becomes returned only when all reserved quantities have been completed. The authenticated `POST /api/returns`, `POST /api/returns/requests`, and `POST /api/returns/complete` routes require an already-persisted `return_sale` grant; login alone does not create authority. Legacy full-return movements are migrated to explicit return-event/completion references.

**Remaining release controls:** credit-note numbers and refund Payment evidence are supplied by the authorized caller; the system verifies their persisted links and approval but does not independently query an external payment provider. Partial/excess settlement remains unsupported. The UI/operator workflow must obtain the quote first, complete the actual refund through the payment provider, persist the completed refund Payment, then submit Finance approval and complete the return. Never mark a provider refund successful before external confirmation.

### 5. Payment completion versus Settlement/Finance recognition
**Severity: high for partial settlements and returns.** PaymentApplication still records payment lifecycle transitions without directly creating settlement or ledger rows. For a fully paid, invoice-linked Commerce Sale, the new Finance-owned orchestration can create Settlement and durable Finance recognition only after validating all persisted links, currency/party equality, an active creditor-owned account, an exact approved agent action, and recorded human approval. It fails closed for partial/excess settlement. Fulfillment is never treated as payment evidence. Refunds and return reversals remain separate governed operations.

### 6. Automatic invoice issuance and tax compliance remain unconfigured
**Severity: high — unchanged by design.** Checkout records a receipt. Invoice issuance requires a separate authorized issuer, valid grant, and unique invoice number. No issuer policy, numbering authority, or jurisdiction-specific tax rules were invented. The receipt must not be represented as a tax invoice.

## Test plan and release gate

- Regression: a Settlement-linked Payment consumes the same Obligation capacity as a directly linked Payment.
- Regression: mismatched nullable Invoice/Obligation references are rejected by the application without writes.
- Regression: a failure while validating the persisted Payment rolls back both the Payment row and its domain-history record.
- Database guards: direct SQL cannot insert or update the same mismatch.
- The first CI run found two migration-registry expectations that enumerated migrations through 0052. Both expected-version lists are updated to include 0053; rerun CI against the new head before merge. Earlier run result: 1,421 passed, 2 failed, both due to these stale expected migration lists (not runtime behavior).

## Recommended next engineering slice

1. Define a first-class, append-only Sale Return event with idempotency and per-line cumulative quantity limits.
2. Define owner-approved reversal/credit-note rules across Invoice, Obligation, Payment, Settlement, and Finance; do not invent a new Refund concept contrary to SPEC-0023.
3. Add governed cash/deferred tender selection only after the commercial contract says what each option records and what it does not imply.
4. Add issuer configuration and authorized invoice numbering/orchestration, preserving the distinction between a receipt and a tax invoice.
