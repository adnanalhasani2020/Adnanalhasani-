# Commerce sale and invoice lifecycle audit

**Reviewed:** 2026-10-19  
**Scope:** commercial storefront, stock-gated checkout, sale lifecycle, persisted Commerce invoices, and Finance boundary.  
**Status:** updated implementation audit; see the current PR's CI for executable verification. This is not production or tax-compliance certification.

## Executive result

The repository now has a governed, opt-in path from checkout to Invoice and a Finance-owned finalization path from a completed, invoice-linked Payment to Settlement and durable Finance recognition. It is not an autonomous default workflow: the checkout API remains receipt-only unless an authorized caller supplies the persisted invoice-issuer grant, issuer, invoice number, and (when applicable) Obligation. After the external Payment is completed, an approved Finance action must explicitly finalize the Settlement and recognition. The system does not invent an issuer, invoice number, grant, or payment evidence; doing so would weaken financial controls.

The current printable `/invoices/{sale_id}` route is explicitly a backward-compatible alias for a sales receipt; the page correctly states that it is not a tax invoice. Do not rename it or represent it as a legally compliant invoice.

## Current implementation update (2026-10-26)

- `CommercialCheckoutApplication.checkout` can now issue an operational Commerce Invoice in the same SQLite transaction when the caller supplies an active issuer, unique invoice number, and exact persisted `issue_invoice` grant. Missing/invalid invoice authorization rolls back the sale, stock movement, and checkout receipt; same-key retries replay the saved invoice identity. This is opt-in and does not invent an issuer or grant.
- `PersistedSettlementRecognitionApplication.settle_and_recognize_commerce_sale` verifies that a completed Payment, issued Invoice, Sale, Obligation, and creditor-owned Financial Account form one persisted chain. It creates the Settlement and recognized FinancialTransaction/LedgerEntry atomically under the existing Finance agent grant and recorded human approval. Any validation or ledger failure rolls back the new Settlement. It does not infer payment from fulfillment and only supports a full single-obligation settlement.
- These additions connect the explicit invoice and completed-payment records to final Finance recognition, but they do not create a Payment or external payment evidence automatically. Callers must still record/complete Payment through `PaymentApplication`; partial settlement remains unsupported.

## Lifecycle reviewed

1. **Catalog:** only active offerings with one effective price and known positive stock are exposed.
2. **Checkout:** `CommercialCheckoutApplication.checkout` validates identifiers, quantity, session/grants, and idempotency; uses `BEGIN IMMEDIATE`; snapshots the sale price; writes sale, stock movement, and a persisted receipt in the same transaction.
3. **Receipt retrieval:** authenticated receipt history and direct lookup are restricted to the buying account. The printable receipt is truthful about its status.
4. **Invoice issuance:** `InvoiceApplication.create_invoice` validates a persisted Sale, issuer, invoice number, timestamps, and an exact active grant; it records domain history. Checkout can now invoke invoice issuance atomically when explicit issuer, invoice-number, and grant inputs are provided; the default checkout remains receipt-only.
5. **Void/payment boundary:** voiding is blocked when an obligation or payment is linked, avoiding silent financial-history changes. Invoice issuance itself does not create payments, settlements, or ledger entries; the separate guarded Finance finalization method handles Settlement and recognition after a completed, linked Payment exists.
6. **Finance boundary:** the existing audit `docs/audits/2026-10-18-finance-reuse-for-hospital.md` records that payment is not financial recognition and that a verified persisted end-to-end settlement/ledger path has not been established.

## Four highest-priority gaps

### 1. Governed sale-to-invoice orchestration
**Impact: reduced; configuration remains required.** Checkout now supports opt-in invoice issuance in the same SQLite transaction using the existing `InvoiceApplication`, but only when an issuer, invoice number, and exact persisted grant are supplied. The default checkout still creates a receipt only.

**Remaining:** configure and manage issuer identity/number allocation through an authorized business workflow. Do not invent a default issuer or claim tax compliance. The invoice number remains caller-supplied; concurrent number allocation must be provided by the owning issuer configuration before this can be treated as a complete production invoice service.

### 2. Governed Payment → Settlement → durable ledger path
**Impact: reduced for fully settled invoice-linked payments; partial settlement remains unsupported.** The new Finance-owned `settle_and_recognize_commerce_sale` verifies the Sale/issued Invoice/completed Payment/Obligation/Financial Account chain and atomically creates a Settlement plus durable Finance recognition under explicit agent authorization and human approval. Retries reuse the existing Settlement and recognition rows.

**Remaining:** checkout does not create a Payment or assert external payment success. `PaymentApplication` must persist the completed payment first; the caller must then invoke the guarded settlement/recognition operation. Partial settlement and automated provider confirmation are intentionally not inferred.

### 3. Legal/tax invoice semantics are not configured
**Impact: high for tax compliance.** The existing Commerce invoice lifecycle is a generic persisted invoice, not evidence of compliance with Yemen or Saudi tax law. The storefront receipt correctly disclaims tax-invoice status.

**Safe fix required:** configure jurisdiction, issuer registration details, mandatory invoice fields, numbering, rounding, tax calculation, credit-note/void rules, and any mandated reporting only after the target jurisdiction and legal requirements are established. Keep the receipt label truthful in the meantime.

### 4. Returns/refunds are not demonstrated as a complete reversal workflow
**Impact: high for inventory and financial integrity.** A sale is fulfilled and inventory movement is recorded, but this review did not establish a complete governed path that reverses inventory, invoice/obligation, payment, and financial recognition together.

**Safe fix required:** add a separate authorized return/refund application with source-sale quantities, duplicate-request protection, append-only compensating inventory movements, credit-note/reversal policy, and reconciliation. Never edit or delete the original sale to simulate a return.

## Existing controls worth preserving

- SQLite write serialization and transaction rollback in checkout.
- Idempotency fingerprints and stored checkout responses.
- Effective-price ambiguity is rejected rather than silently resolved.
- Unknown/stale inventory observations fail closed.
- Invoice issuer/number uniqueness is enforced by the schema.
- Invoice lifecycle uses exact persisted grants, optimistic versions, and domain history.
- Invoice voiding is blocked when linked financial activity exists.
- Receipt endpoints are account-scoped and receipt wording avoids claiming tax compliance.

## Verification limits and next steps

This is a source-level review, not a full runtime test or production certification. Tests were inspected but not executed here. Before enabling automatic invoice issuance, add integration tests for: authorized issuer setup; missing/expired/wrong-scope grant; unique numbering under concurrent checkout; duplicate checkout replay; invoice failure rollback/outbox recovery; and proof that no payment or ledger recognition is fabricated. Then run the full test suite and migration checks on the exact commit.

**Release recommendation:** keep checkout + receipt behavior as-is; do not enable automatic invoices, tax compliance claims, or automatic ledger posting until the four gaps above are addressed through their canonical governed boundaries.
