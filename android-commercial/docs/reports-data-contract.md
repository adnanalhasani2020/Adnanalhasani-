# Reports data contract (Android commercial)

This document defines the source and calculation boundary for the report center. It deliberately distinguishes operational summaries from formal financial statements.

## Date and currency rules
- User-selected dates are inclusive. Timestamped records are filtered from the local zone's start of the start date up to, but excluding, the start of the day after the end date.
- Cloud money fields ending in `_minor` are integer minor units. UI display converts them using the ISO currency's configured fraction digits. Do not sum amounts across different currency codes.
- Local legacy `SaleRecord`, `SettlementRecord`, and `ExpenseRecord` values are displayed as the local app stores them; they are not implicitly converted to cloud minor units.
- Cloud/local sources are not merged.

## Implemented operational reports
- **Completed sales:** `loadCommercialCloudSales` / local `sales_json`; only records whose sale status is `completed` contribute to sales summaries. The gross completed-sales total is not net sales because the return/refund ledger is not subtracted here.
- **Invoice details and line-item sales (cloud):** `loadCommercialCloudSaleDocuments` reads the sale, line snapshots, payment status, discount, tax, and return projections from the organization's existing sales records. The item report uses captured invoice-line quantities and line totals, not current product prices.
- **Sales discounts and recorded taxes (cloud):** uses `discount_minor` and `tax_minor` stored on completed invoices. No rate is inferred. Local reports are unavailable for these measures because the local sale model does not persist these fields.
- **Payments (cloud):** the successful-payment report includes only rows whose payment status is `succeeded`. The status listing can show other statuses but must not be treated as collected cash. It does not add journal entries as a second payment.
- **Expenses:** local records come from `expenses_json`; cloud records come from `commercial_expense_vouchers`. Cloud vouchers with a non-empty `canceled_at` are excluded. This is a recorded-expense report, not a complete income statement.
- **Purchase invoices:** values come from supplier invoices nested under purchase documents, not from purchase-order totals. Cancelled/void/reversed invoice states are excluded. Outstanding amounts are shown only where a supplier-obligation record exists.
- **Supplier invoice obligations:** grouped by supplier and currency using recorded `amount_due_minor - amount_paid_minor`, floored at zero for display. This is not a full supplier account balance when separate supplier returns or adjustments exist.
- **Returns:** rows expose the return's approval, refund, and restock statuses separately. An approved refund amount is not described as money already refunded; a return request is not automatically counted as a completed stock return.
- **Inventory:** current stock and reorder alerts are current snapshots. They are not historical stock balances at the selected report date.
- **Posted ledger movement:** `loadCommercialCloudPostedJournalLines` joins organization-scoped journal entries with status `posted`, their journal lines, and ledger accounts. Draft/unposted entries are excluded. The trial-balance view aggregates posted debit and credit amounts by account and currency up to the selected end date and exposes the debit/credit difference. It is a review view of the available posted ledger, not a certification that every operational module has been fully mapped.

## Deliberately unavailable as formal statements
Customer-wide balances, full supplier balances, cash-flow opening/closing balances, income statement, balance sheet, tax return, historical inventory valuation, and profit/COGS are not labelled as authoritative until all relevant posted sources, reversals, returns, cost valuation, and reconciliations are verified. Never infer these from invoice totals alone.

## Exports
- **CSV:** contains the selected report's displayed rows plus report title, inclusive date range, source mode, and export timestamp. It uses UTF-8 with BOM and quoted/escaped cells for spreadsheet compatibility.
- **XLSX:** generated as an Office Open XML ZIP workbook (not renamed CSV), with UTF-8 inline strings, XML escaping, a right-to-left worksheet view, and the same selected rows and report metadata as CSV.
- **PDF export and sharing:** the Android screen writes a paginated PDF through `PdfDocument`, saves it through the Storage Access Framework, and opens the Android share sheet with read permission. It is a basic text layout, not a certified Arabic financial-statement renderer; verify Arabic shaping, pagination, and print appearance on target devices before relying on formal output. Print is exposed only if a compatible destination appears in the share sheet; a dedicated print-preview flow is not implemented.
- Exported amounts are the same formatted values shown in the report rows; they are not a substitute for underlying accounting detail or financial-statement certification.
