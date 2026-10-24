# Commercial documents: integration contract and delivery plan

> Status: schema-grounded implementation plan. This document does not claim that all document workflows or Android screens are already implemented.

## Product requirement

Ruh must behave as a connected commercial system, not a collection of isolated forms. Every operational screen must use the authenticated organization's cloud data, have a polished Arabic RTL mobile layout, and clearly distinguish draft/local state from server-confirmed state.

## Existing database objects to reuse

The live Supabase schema already contains these relevant objects; do not create similarly named duplicates without proving a gap:

- Sales: `commercial_sales`, `commercial_sale_items`
- Customers and suppliers: `commercial_customers`, `commercial_suppliers`
- Purchase orders: `commercial_purchase_orders`, `commercial_purchase_order_items`
- Payments and allocation: `commercial_payments`, `commercial_payment_allocations`
- Operational account transactions: `commercial_account_transactions`
- Sales returns: `commercial_sales_returns`, `commercial_sale_return_items`
- Inventory: `commercial_inventory_balances`, `commercial_stock_movements`
- Accounting: `commercial_ledger_accounts`, `commercial_journal_entries`, `commercial_journal_lines`
- Document metadata: `commercial_documents`

Existing trusted database functions include `commercial_checkout_sale`, `commercial_request_sale_return`, `commercial_decide_sale_return`, `commercial_inspect_sale_return_item`, `commercial_restock_approved_sale_return`, and `commercial_post_journal_entry`. Confirm each function's exact contract and authorization before integrating it; never infer parameters or bypass its guards.

## Important implementation boundaries found

1. The Android app's existing local sale dialog remains single-item. A dedicated cloud invoice composer has now been added and wired to a multi-line cash-checkout RPC on this review branch; it persists an idempotency key before sending, validates visible stock locally, and relies on the server for authoritative price/stock validation. CI and authenticated end-to-end verification are still required before release.
2. The app README explicitly marks cloud credit sales, settlements, expenses, and customer debt workflows as incomplete/local-only. Do not present those local records as cloud accounting.
3. The existing sales-return tables have protected write policies and RPC-based lifecycle. The client must call the authorized workflow, not write directly to the return tables.
4. Purchase-order tables exist, but their presence alone does not prove that purchase invoices, receiving, supplier settlement, or stock posting are integrated end-to-end.
5. The journal tables enforce posted-line immutability. Financially posted documents must be reversed with a traceable compensating entry rather than edited or deleted.
6. Warehouse and storage-location masters exist, but stock movement into individual storage locations is not yet wired. Continue preserving current site-level inventory until a safe migration path is verified.

## Verified implementation update — 2026-10-24

The Android cloud invoice projection has been expanded to read the existing invoice status, customer contact fields, payment records, and sales-return status/refund/restock summaries alongside the invoice lines. Invoice payment status is now derived from confirmed `succeeded` payment rows rather than trusting a sale row that says `paid` when no payment record exists. This prevents the UI from showing an unpaid invoice as paid; it does not create the missing server-side payment. These are still display-only projections and do not imply that payment allocation, refunds, or accounting posting are implemented.

The app now avoids creating a synthetic device-local invoice when a cloud checkout succeeds but refreshing the cloud history fails. In cloud mode, local-only debt settlement, expense entry, and mixed local-finance reports are blocked with an explicit message rather than presenting local writes as cloud-confirmed balances.

Still not complete and must remain gated:
- An additive multi-line cash-checkout RPC has now been drafted on this review branch. It validates the invoice lines, rejects duplicate offerings and mixed currencies, locks inventory in stable offering order, writes one sale with multiple sale items and stock movements, and creates one idempotent successful cash payment in the same transaction. It is connected to the new Android cloud invoice composer on this review branch, but is not yet applied to the live database or verified by completed CI/live authenticated tests. Do not call it production-ready until those gates pass. Calling the existing single-item RPC repeatedly remains unacceptable.
- Cloud credit sales and collections require server-side customer linkage, payment records and allocation semantics, party balances, idempotency, authorization, and journal posting/reversal behavior.
- Existing checkout currently updates stock and writes a stock movement, but the audited function body does not create a `commercial_payments` row or a journal entry. Therefore do not claim the full cash/accounting lifecycle is complete until this gap is addressed in a schema-grounded migration and verified against current constraints and policies.
- Payment allocation currently references `order_id`; do not assume it can be allocated directly to `commercial_sales` without checking the intended order/sale relationship and enforcing no-over-allocation.
- CI/build and live authenticated end-to-end tests are still release gates. No APK should be described as tested until the Android build and focused tests pass.
### Cash checkout hardening — 2026-10-24

An additive migration now wraps the existing authorized single-item checkout RPC and writes one `commercial_payments` row with status `succeeded` in the same database transaction as the sale and stock movement. A second additive migration drafts the corresponding one-transaction multi-line cash checkout. The payment idempotency key is derived from the server-generated sale UUID, and the unique organization/key constraint prevents duplicate cash payment rows on retries. The wrapper retains the existing public invoker boundary and delegates sale authorization and inventory locking to the existing private function.

This migration is committed to the review branch but has **not** been applied to the live database. Apply only after migration review and CI validation. It closes the missing cash-payment-record gap for the existing single-item flow; it does not complete multi-line checkout, customer collections, credit sales, refunds, or general-ledger posting. Do not manufacture a journal entry until the organization's ledger accounts and posting policy are configured and verified.

## Target document workflows

### Sales
Quote (draft/sent/accepted/expired) → sales invoice (draft/posted/partially paid/paid) → optional delivery → receipt/payment allocation → customer statement.

### Purchasing
Purchase request → purchase order → partial/full goods receipt → supplier invoice → supplier payment/allocation → supplier statement.

### Returns
A return must reference the source invoice and its original lines. The server must enforce cumulative returnable quantity, approval, refund amount, inspection disposition, stock restock, payment reversal/allocation, and idempotency. Partial returns must not be simulated by editing the original invoice.

### Shared document contract
Every document screen should display document number, date, state, party, organization/activity/site/warehouse context where relevant, line items, unit and conversion factor, quantity, unit price/cost, line discount, document discount, taxes only when configured, additional charges, grand total, paid amount, outstanding balance, payment terms, notes, and a traceable source-document link.

- Keep money in integer minor units and preserve currency code.
- Keep quantities in an explicit numeric type with validated unit conversions; never silently round a fractional conversion.
- Save line snapshots (description, unit, price, tax/discount basis) so future master-data edits do not rewrite old documents.
- Use server-generated document numbers and organization-scoped authorization.
- Use idempotency keys for every retryable write.
- Drafts may be edited under policy; posted records are immutable except through explicit cancel/reversal workflows.
- A quote converted to an invoice must retain its source reference and must not create inventory or accounting effects twice.
- Every successful posting must update the relevant stock ledger and accounting/party ledger in the same transaction where required.
- Show loading, empty, validation, offline, and server-error states explicitly. Never claim a save succeeded until the server confirms it.

## Android UX requirements

- Arabic-first RTL, consistent spacing, clear hierarchy, high-contrast totals, responsive cards and tables that work on a phone.
- A document list with search, date/status filters, party filters, totals, and a clear create action.
- A multi-line editor with product/barcode search, unit selector, quantity, price/discount permissions, availability, add/remove line actions, and live totals.
- A persistent summary panel for subtotal, discounts, configured taxes, charges, total, paid, and remaining.
- Separate actions for Save Draft, Post/Confirm, Record Payment, Print/Share, and Reverse/Cancel according to state and permission.
- A printable/shareable invoice layout with business identity and externally hosted image URLs only; do not upload product images to Supabase Storage.
- Avoid one enormous dialog for a complex invoice. Use a dedicated screen with sections and a review/confirmation step.

## Delivery order

1. Verify exact live columns, constraints, RLS policies, indexes, RPC signatures, and existing tests for sales, payments, purchasing, returns, inventory, and journals.
2. Replace the single-item-only cloud sales composer with a dedicated multi-line invoice screen backed by a transactional server function; do not enable unsupported credit or tax behavior prematurely.
3. Add server-backed customer/supplier selection and payment allocation with reliable balances.
4. Complete purchase order and receipt workflow before posting stock increases.
5. Integrate return request/review/inspection/refund/re-stock through the existing guarded RPCs.
6. Add quote conversion, statements, print/share, and operational reports using the same canonical documents and ledger entries.
7. Build the Android app and run focused tests after each vertical slice; publish an APK only after a successful build and provide a real artifact link.

## Merge and release gate

Do not merge a PR solely because its title sounds relevant. Confirm its base/head, divergence, latest commit CI, tests, security behavior, and overlap with changes already on main. Do not apply speculative production DDL or disable RLS to speed up UI integration.
