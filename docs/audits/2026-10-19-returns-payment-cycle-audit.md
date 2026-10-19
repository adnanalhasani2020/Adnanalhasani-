# Returns and Payments lifecycle audit

**Reviewed:** 2026-10-19  
**Scope:** Sale return transitions, append-only stock restoration, Invoice links, Payment lifecycle, Settlement capacity, and Finance boundaries.  
**Method:** source and migration review; automated execution is delegated to repository CI after the proposed commit. This is not a production or legal compliance certification.

## Executive summary

The repository has sound foundational controls: checkout is transactionally persisted and idempotent; inventory movements and domain history are append-only; sale and payment transitions require persisted authorization; Payment, Settlement, FinancialTransaction, and LedgerEntry remain distinct. The full returns/payment journey is nevertheless incomplete. Two concrete Payment integrity defects are corrected in this change, with regression tests and database-level protection.

## Findings and disposition

### 1. Settlement-linked payments could be omitted from remaining-obligation capacity
**Severity: high — fixed.** The create-payment capacity check originally reserved capacity using only the direct payments.obligation_id field. The persisted reader already accounted for a Payment linked indirectly through settlements. A Payment with a NULL direct obligation but an existing Settlement could therefore be ignored when another Payment was created against the same Obligation. The new creation check uses the same direct-or-settlement relationship as the persisted reader and rejects over-allocation before writing.

### 2. Invoice/Obligation mismatches could be accepted when the Invoice had no Obligation
**Severity: high — fixed.** The application previously rejected a mismatch only when Invoice.obligation_id was non-NULL. That allowed a Payment to link an Invoice with no Obligation to an unrelated Obligation. The application now requires exact nullable-reference equality; a new migration enforces that invariant for inserts and updates at the SQLite boundary. Existing rows are not rewritten and remain subject to explicit reconciliation.

### 3. Payment creation validated the persisted record after its transaction had committed
**Severity: medium — fixed.** The returned Payment was reloaded only after the atomic transaction completed. If persisted-record validation failed, the caller could receive an exception after the write had already committed. Validation now occurs inside the transaction/savepoint, so an error rolls back the operation owned by the application.

### 4. Return lifecycle restores stock only for a single, fully reconciled checkout line
**Severity: high — intentionally not broadened in this patch.** Return is authorized, blocked while an Invoice remains issued, checks the original stock movement against the sale line, and appends a positive compensating movement in the same transaction as the Sale transition. It does not support partial returns or multi-line/multiple return events. The current schema uniquely identifies one sale_return movement per Sale and the Sale lifecycle has a terminal returned state. Adding partial returns safely requires a canonical return-event identity, cumulative returned-quantity guards, movement references to each return event, and a governed Invoice/Obligation/Payment/Finance reversal policy. Simply weakening uniqueness or allowing repeated transitions would risk stock duplication.

### 5. Payment completion is not settlement, refund, or accounting recognition
**Severity: critical for financial claims — unchanged by design.** PaymentApplication records authorized Payment state transitions but does not create Settlement, FinancialTransaction, or LedgerEntry. No cash/deferred checkout mode or provider confirmation should be inferred from Sale fulfillment. A governed reversal/refund flow requires the owning domains and reconciliation rules; it must not mutate original payment/sale history or post directly to Finance.

### 6. Automatic invoice issuance and tax compliance remain unconfigured
**Severity: high — unchanged by design.** Checkout records a receipt. Invoice issuance requires a separate authorized issuer, valid grant, and unique invoice number. No issuer policy, numbering authority, or jurisdiction-specific tax rules were invented. The receipt must not be represented as a tax invoice.

## Test plan and release gate

- Regression: a Settlement-linked Payment consumes the same Obligation capacity as a directly linked Payment.
- Regression: mismatched nullable Invoice/Obligation references are rejected by the application without writes.
- Database guards: direct SQL cannot insert or update the same mismatch.
- CI must run the full Python tests and migration checks against the exact PR head before merge.

## Recommended next engineering slice

1. Define a first-class, append-only Sale Return event with idempotency and per-line cumulative quantity limits.
2. Define owner-approved reversal/credit-note rules across Invoice, Obligation, Payment, Settlement, and Finance; do not invent a new Refund concept contrary to SPEC-0023.
3. Add governed cash/deferred tender selection only after the commercial contract says what each option records and what it does not imply.
4. Add issuer configuration and authorized invoice numbering/orchestration, preserving the distinction between a receipt and a tax invoice.
