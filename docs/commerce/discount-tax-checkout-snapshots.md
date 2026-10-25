# Commercial checkout discount and tax snapshots

Checkout must persist explicit discount and tax amounts with the invoice. It must not infer a tax rate, jurisdiction, or policy. The final total is subtotal minus discount plus tax, all in integer minor units. Negative amounts and discounts above subtotal are rejected.

For accounting, receivables are debited for the final total, revenue is credited for the subtotal, a non-zero discount requires an explicitly configured sales_discounts mapping, and non-zero tax requires an explicitly configured sales_tax_payable mapping. If a required mapping is missing, checkout fails closed. Inventory and cost-of-goods-sold entries remain in the same database transaction.

The extended checkout RPC accepts explicit discount_minor and tax_minor arguments. Existing six-argument callers remain compatible, but clients must call the extended RPC to use these amounts.

Before merge or release, apply the migration to an isolated disposable database and run pgTAP plus authenticated integration tests for cash/credit, idempotent retries, changed request fingerprints, invalid amounts, balanced journals, payment reconciliation, rollback, and concurrent requests. Source changes alone do not prove those tests passed.
