# Sale integrity hardening — 2026-10-19

## Scope

Harden the persistence boundary for the existing commercial checkout. This change does not introduce a second sale implementation and does not alter the separately governed invoice, payment, settlement, or Finance workflows.

## Change applied

- Added migration `0060_sale_line_checkout_evidence_guard.sql`.
- New `sale_lines` rows are rejected unless the source Sale is already `completed` or `fulfilled` and `product_name_snapshot` is non-empty after trimming.
- Existing legacy rows are not rewritten; the trigger applies to inserts only. The existing immutable update/delete guards remain authoritative for line history.
- Added Stage 60 regression tests for persisted product/price/total snapshots, rejection of blank product-name snapshots, and rejection of sale lines attached to an unfulfilled Sale.

## Integrity contract

A checkout remains successful only when its existing single SQLite transaction commits the governed Sale lifecycle, immutable sale line with the purchase-time price and product-name snapshot, append-only inventory movement, and idempotent receipt. The receipt is not a tax invoice, and this guard does not claim to establish payment, settlement, or ledger recognition.

## Verification status

Source-level review confirms the migration is ordered after the sale-line snapshot migration and before the repository's terminal migrations. The repository connector does not provide a local test runner, so these tests have **not been executed in this session**. Run `pytest tests/test_commercial_checkout.py tests/test_stage60_sale_line_checkout_evidence.py` and the full CI suite against the resulting commit before release.

## Release boundary

Do not infer legal invoice compliance or payment/financial recognition from a fulfilled Sale. Preserve the existing explicit invoice issuer authorization and Finance-owned recognition boundaries.
