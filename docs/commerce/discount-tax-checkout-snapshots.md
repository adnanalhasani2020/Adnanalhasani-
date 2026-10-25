# Commercial checkout discount and tax snapshots

Checkout persists explicit discount and tax amounts with the invoice. It must not infer a tax rate, jurisdiction, or policy. The final total is subtotal minus discount plus tax, all in integer minor units. Negative amounts and discounts above subtotal are rejected.

For accounting, receivables are debited for the final total, revenue is credited for the subtotal, a non-zero discount requires an explicitly configured `sales_discounts` mapping, and non-zero tax requires an explicitly configured `sales_tax_payable` mapping. If a required mapping is missing, checkout fails closed. Inventory and cost-of-goods-sold entries remain in the same database transaction.

The extended checkout RPC accepts explicit `discount_minor` and `tax_minor` arguments. The legacy six-argument function is retained for existing callers; clients must call the extended RPC to use these amounts.

## Validation notes

- The migration is designed for review and must be exercised against a disposable isolated database before release.
- Required coverage includes pgTAP schema checks and authenticated integration tests for cash/credit, idempotent retries, changed request fingerprints, invalid amounts, balanced journals, payment reconciliation, rollback, and concurrent requests.
- These tests have not been confirmed as run by this branch's changes. A successful source diff or merge is not evidence that database integration tests passed.
- Do not apply this migration to production as a test.