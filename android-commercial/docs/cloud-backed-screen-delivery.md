# Cloud-backed commercial screens — delivery note

## Added in this slice

The Android “More” menu now links to dedicated, Arabic-first, RTL list screens for:

- Cloud customer directory
- Supplier directory
- Purchase orders
- Payment records
- Sales returns
- Journal entries
- Ledger accounts
- Cloud sales invoice history with customer, payment status, totals, and each invoice line
- Purchase order detail and receiving-progress screen with supplier name, expected dates, line quantities, received quantities, and unit costs
- Cloud stock-alert dashboard with available quantity, reorder threshold, out-of-stock and low-stock filters
- Department-to-warehouse assignment workflow reusing the existing organization-scoped assignment table, with duplicate-link protection and database scope validation

The screens provide search, refresh, loading/error/empty states, status badges, source references, and a read-only indicator. Their queries use an explicit allow-list of existing tables and columns from the reconciled schema and filter by the saved organization ID. Session refresh uses the existing encrypted Supabase refresh-token storage.

## Important correctness boundary

The stock-alert dashboard reuses the existing organization/activity/location-scoped inventory projection. Its displayed quantity is available-to-sell (on-hand minus reserved), not raw on-hand; the screen is read-only and does not create stock movements.\n\nThese are cloud-backed read-only views, not full create/edit/posting workflows. No UI action writes directly to payment, return, journal, or ledger tables. This is intentional: purchasing, customer debt, supplier settlement, refunds/restocking, and journal posting must use the exact existing guarded RPC contracts and transactional behavior. Do not label local-only debt/expense records as cloud-confirmed.

The purchase list currently displays the supplier ID rather than a joined supplier name, and monetary values are shown in minor units because the UI must not guess currency precision. The cloud invoice history now displays all lines for each sale using the existing sale/customer/item relationships; it remains read-only. The purchase screen also joins the existing supplier and order-item records to show ordered versus received quantities, but does not create orders or post receipts. These should be refined only after a schema-grounded join and currency-format contract are confirmed.

Department-to-warehouse links are managed from the existing master-data screen. The client checks for an existing pair before inserting, while authenticated-user RLS and the database scope trigger remain authoritative. This operation changes only the relationship table; it does not change stock balances or movements.

## Next UI slices

1. Replace the single-item sale dialog with a multi-line invoice editor only after a single atomic server operation can accept and validate all lines, units, pricing, stock, idempotency, and the resulting document.
2. Add customer/supplier create/edit forms through approved server-side contracts.
3. Complete purchase order items, receiving, and supplier settlement as separate state-aware screens.
4. Wire returns through request → decision → inspection → refund/restock using the existing guarded RPCs.
5. Add receipt allocation and statements only when authoritative cloud allocation APIs are confirmed.
6. Add trial balance/income statement/balance sheet only after the canonical posted double-entry model and account classification rules are proven.

## Validation boundary

The Android debug APK build and both repository pytest workflows passed on PR #540 (commit `ffc86eba7d506e76af4a2e0a0842fbbd01263325`). Static contract tests now guard the query allow-list, organization scoping, read-only behavior, route coverage, and error/read-only UI labels. CI success does not prove RLS permits every intended role; authenticated staging smoke tests are still required before release. No production records were created or modified by these read-only screens.


## Master-data status filtering

The master-data screen now displays each record's current status and provides `All`, `Active`, and `Inactive` filters alongside search. Filtering is local and read-only; it does not activate, deactivate, or otherwise mutate warehouse, category, department, or storage-location records. The displayed record count distinguishes the total loaded rows from the filtered count.


## Active hierarchy options

When creating a storage location, the form offers only active warehouses and active parent locations within the selected warehouse. Category and department hierarchy selectors likewise offer active parent records only. The root-level option remains available. This is a UI guard for clearer data entry; database constraints and authorization remain authoritative.

## Master-data create submission guard

The warehouse/category/department/storage-location create dialog disables repeat saves while a request is in flight, trims the submitted code and name, keeps the form open on failure, and shows the returned error in the dialog. A successful cloud write followed by a successful refresh closes the form. This reduces accidental repeated submissions and prevents a failed request from silently discarding the user's input; database uniqueness and authorization remain authoritative.

## Dedicated unit-of-measure directory screen

The More menu now exposes a dedicated read-only screen for the existing active `commercial_unit_of_measure` directory. It supports search by code/name/dimension, dimension filters, refresh, loading/error/empty states, and displays decimal precision. It intentionally does not create or edit global units and does not imply product-specific case/carton conversion factors; those require a separate, validated per-product conversion contract.


## Customer and supplier directory details

Customer and supplier cards now expose the contact and master-data fields already returned by the organization-scoped Supabase queries: email, phone, supplier code, payment terms, and current state when present. Missing fields are omitted instead of displaying literal `null` values. This is a read-only presentation change: it adds no schema, does not estimate customer balances, and does not enable unverified customer/supplier writes.
