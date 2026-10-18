# Operational sales report API

The authenticated commercial API exposes a read-only operational sales report:

- `GET /api/reports/sales` — JSON report for recorded sales and immutable sale-line snapshots.
- `GET /api/reports/sales?from=2026-10-01&to=2026-10-18` — inclusive date range, using `YYYY-MM-DD`.
- `GET /api/reports/sales?format=csv&from=2026-10-01&to=2026-10-18` — CSV export for spreadsheet analysis.

Both date filters are optional. If supplied, each must be a valid calendar date; `from` must not be later than `to`. The end date is inclusive. Requests require the existing commercial session cookie; unauthenticated requests receive the existing `401 Unauthorized` response.

The JSON response contains the number of distinct sales, counts by persisted sale state, rows with sale ID/time/state/quantity/currency/recorded line total, and recorded line totals grouped by currency. Empty ranges return an empty `items` array and zero count. CSV contains the same row-level operational fields.

**Financial boundary:** recorded sale-line totals are operational price snapshots only. They are not recognized revenue, settlement evidence, receivables, or accounting balances. The report creates no financial records and does not change checkout or inventory behavior.
