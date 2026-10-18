# Operational sales report API

The authenticated commercial API exposes a read-only operational sales report:

- `GET /api/reports/sales` — JSON report, defaulting to the newest 50 matching sales.
- `GET /api/reports/sales?from=2026-10-01&to=2026-10-18&limit=50&offset=0` — filtered JSON page.
- `GET /api/reports/sales?format=csv&from=2026-10-01&to=2026-10-18` — CSV export of **all matching results**, not only the current JSON page.

Both date filters are optional. If supplied, each must be a valid calendar date in `YYYY-MM-DD` format; `from` must not be later than `to`. The end date is inclusive. Requests require the existing commercial session cookie; unauthenticated requests receive the existing `401 Unauthorized` response.

**Authorization blocker:** the current commercial session proves identity only; this API does not yet enforce an approved report role/permission and returns global matching sales. Any authenticated commercial account can therefore see other customers' transactions. Do not merge or enable this endpoint until the project owner approves its audience and authorization contract. No role, action code, or customer-only interpretation is assumed here.

## JSON pagination

- `limit`: optional integer from 1 to 200; defaults to 50.
- `offset`: optional non-negative integer; defaults to 0.
- Ordering is stable by `occurred_at DESC, sale_id`.
- Pagination selects sales before joining sale lines, so a sale with multiple currency rows is not split between pages.
- `total_sales` is the number of matching sales across all pages; `returned_sales` is the number of distinct sales represented on this page; `has_more` says whether more matching sales remain after this page.
- `items` may contain multiple rows per sale when its recorded lines span currencies. Each row contains sale ID/time/state/quantity/currency/recorded line total.
- `sale_counts_by_state` and `recorded_line_totals_by_currency` summarize **all matching results**, not just the current page. State names reflect the persisted `sales.state` values (including the legacy `completed` label); the domain model separately resolves that legacy label to its canonical fulfilled state.
- `summary_scope` and `items_scope` explicitly distinguish all-match summaries from current-page rows.

Invalid date ranges, malformed dates, non-integer pagination values, limits outside 1–200, and negative offsets return `400 Bad Request`. Empty ranges return zero totals and an empty `items` array.

## CSV export

CSV export applies the date filters but deliberately ignores `limit` and `offset`, exporting every matching row. The response includes `X-Report-Export-Scope: all-matching-results` to make this behavior explicit. The CSV columns are `sale_id, occurred_at, state, quantity, currency_code, line_total_minor`.

**Financial boundary:** recorded sale-line totals are operational price snapshots only. They are not recognized revenue, settlement evidence, receivables, or accounting balances. The report creates no financial records and does not change checkout or inventory behavior.
