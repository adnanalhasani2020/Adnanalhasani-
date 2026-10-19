# Sale integrity hardening — 2026-10-19

## Scope

Harden the persistence boundary for the existing commercial checkout. This change does not introduce a second sale implementation and does not alter the separately governed invoice, payment, settlement, or Finance workflows.

## Change applied

- Added migration `0060_sale_line_checkout_evidence_guard.sql`.
- New `sale_lines` rows are rejected unless the source Sale is already `completed` or `fulfilled` and `product_name_snapshot` is non-empty after trimming.
- Added migration `0061_sale_line_product_name_snapshot_match_guard.sql` to reject a line whose product-name snapshot does not match the persisted Product name at insertion time. This validates the snapshot without tying historical lines to later Product renames.
- Added migration `0062_commercial_checkout_receipt_immutable.sql` to make the receipt row immutable. The receipt is the durable idempotency/replay record; direct updates or deletion could corrupt the response returned for a retry or make a committed sale unrecoverable by key.
- Existing legacy rows are not rewritten; the trigger applies to inserts only. The existing immutable update/delete guards remain authoritative for line history.
- Added Stage 60 regression tests for persisted product/price/total snapshots, rejection of blank product-name snapshots, and rejection of sale lines attached to an unfulfilled Sale.
- Fixed an inventory ambiguity: a stock observation and a movement with the exact same instant cannot establish whether the observation already includes the movement. Checkout and catalog availability now fail closed instead of silently counting or ignoring that movement.
- Added a regression test proving an ambiguous stock observation cannot create a second Sale, sale line, movement, or receipt.
- Added a regression test proving direct SQL cannot forge a non-empty but incorrect product-name snapshot.
- Added a regression test proving direct SQL cannot mutate/delete a committed checkout receipt and that the original response still replays without duplicate sale or stock effects.

## Integrity contract

A checkout remains successful only when its existing single SQLite transaction commits the governed Sale lifecycle, immutable sale line with the purchase-time price and product-name snapshot, append-only inventory movement, and idempotent receipt. The receipt is not a tax invoice, and this guard does not claim to establish payment, settlement, or ledger recognition.

## Verification status

Source-level review confirms all three new migration guards are additive and their migration versions are included in both migration-registry assertions. The repository connector does not provide a local test runner, so these tests have **not been executed in this session**. Run `pytest tests/test_commercial_checkout.py tests/test_stage60_sale_line_checkout_evidence.py tests/test_persistence_verification.py tests/test_stage16_sale_offering_activity_consistency.py` and the full CI suite against the resulting commit before release.

## Release boundary

Do not infer legal invoice compliance or payment/financial recognition from a fulfilled Sale. Preserve the existing explicit invoice issuer authorization and Finance-owned recognition boundaries.
