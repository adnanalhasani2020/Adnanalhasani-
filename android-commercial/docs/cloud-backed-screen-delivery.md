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

The screens provide search, refresh, loading/error/empty states, status badges, source references, and a read-only indicator. Their queries use an explicit allow-list of existing tables and columns from the reconciled schema and filter by the saved organization ID. Session refresh uses the existing encrypted Supabase refresh-token storage.

## Important correctness boundary

The stock-alert dashboard reuses the existing organization/activity/location-scoped inventory projection. Its displayed quantity is available-to-sell (on-hand minus reserved), not raw on-hand; the screen is read-only and does not create stock movements.\n\nThese are cloud-backed read-only views, not full create/edit/posting workflows. No UI action writes directly to payment, return, journal, or ledger tables. This is intentional: purchasing, customer debt, supplier settlement, refunds/restocking, and journal posting must use the exact existing guarded RPC contracts and transactional behavior. Do not label local-only debt/expense records as cloud-confirmed.

The purchase list currently displays the supplier ID rather than a joined supplier name, and monetary values are shown in minor units because the UI must not guess currency precision. The cloud invoice history now displays all lines for each sale using the existing sale/customer/item relationships; it remains read-only. The purchase screen also joins the existing supplier and order-item records to show ordered versus received quantities, but does not create orders or post receipts. These should be refined only after a schema-grounded join and currency-format contract are confirmed.

## Next UI slices

1. Replace the single-item sale dialog with a multi-line invoice editor only after a single atomic server operation can accept and validate all lines, units, pricing, stock, idempotency, and the resulting document.
2. Add customer/supplier create/edit forms through approved server-side contracts.
3. Complete purchase order items, receiving, and supplier settlement as separate state-aware screens.
4. Wire returns through request → decision → inspection → refund/restock using the existing guarded RPCs.
5. Add receipt allocation and statements only when authoritative cloud allocation APIs are confirmed.
6. Add trial balance/income statement/balance sheet only after the canonical posted double-entry model and account classification rules are proven.

## Validation boundary

The Android debug APK build and both repository pytest workflows passed on PR #540 (commit `ffc86eba7d506e76af4a2e0a0842fbbd01263325`). Static contract tests now guard the query allow-list, organization scoping, read-only behavior, route coverage, and error/read-only UI labels. CI success does not prove RLS permits every intended role; authenticated staging smoke tests are still required before release. No production records were created or modified by these read-only screens.
