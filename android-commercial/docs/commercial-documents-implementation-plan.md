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

## Shared-organization read access

- Organization-scoped sales, sale lines, and sale-linked stock movements should be readable by any active member of the same organization, while personal records remain visible to their owner.
- This read scope does not grant write access. Financially sensitive payment rows and return approval/inspection actions retain their stricter policies and authorized RPC workflows.
- The proposed migration `20261024170000_organization_sales_read_policies.sql` and its pgTAP assertions are in PR #538; they are not live until reviewed, merged, and applied.

## Important implementation boundaries found

1. The Android app's documented cloud sale path currently supports cash checkout only. It reads a sales history, but the invoice composer is a basic single-item dialog rather than a complete multi-line document editor.
2. The app README explicitly marks cloud credit sales, settlements, expenses, and customer debt workflows as incomplete/local-only. Do not present those local records as cloud accounting.
3. **Confirmed Android debt-display risk (from `CommercialCloudRepository.kt` and `MainActivity.kt`):** the cloud sales mapper sets `paid = total` only when `payment_status = paid`, and sets `paid = 0` for every other status. A `partially_paid` invoice therefore displays a zero paid amount and an overstated outstanding balance. The debt screen's settlement action also updates local preferences and the local settlement list, not a server payment/allocation workflow. The Android debt screen now hides that settlement action in cloud mode and warns that partial-payment balances may be inaccurate. This is a safety guard, not a completed cloud collections workflow: partially paid/pending balances still require an authorized server-side aggregate/payment contract before they can be presented as authoritative.
3. The existing sales-return tables have protected write policies and RPC-based lifecycle. The client must call the authorized workflow, not write directly to the return tables.
4. Purchase-order tables exist, but their presence alone does not prove that purchase invoices, receiving, supplier settlement, or stock posting are integrated end-to-end.
5. The journal tables enforce posted-line immutability. Financially posted documents must be reversed with a traceable compensating entry rather than edited or deleted.
6. Warehouse and storage-location masters exist, but stock movement into individual storage locations is not yet wired. Continue preserving current site-level inventory until a safe migration path is verified.

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
