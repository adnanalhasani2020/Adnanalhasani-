# Financial voucher cancellation and cycle test gate

## Cancellation contract

- A posted operating-expense voucher is never deleted. Cancellation records the actor, timestamp, required reason, and linked reversal journal entry on the original voucher.
- Cancellation locks the voucher row, checks active organization membership and finance role, and reverses the original posted journal using the existing GL reversal function in the same database transaction.
- A repeated request with the same reason returns the already-created reversal. A repeated request with a different reason fails rather than silently changing the audit record.
- Draft/unposted vouchers, vouchers without a journal, unauthorized users, and missing reversal confirmation are rejected. The database transaction rolls back the cancellation marker if reversal fails.
- This migration intentionally does not pretend to cancel supplier payments, customer receipts, refunds, or sales by merely reversing a journal. Those document types also affect payment state, customer/supplier balances, stock, refunds, and source-document lifecycle; each requires a source-specific atomic cancellation workflow and end-to-end tests.

## Required authenticated financial-cycle integration suite

Run against a disposable/staging Supabase project with test organization, users of distinct roles, seeded chart mappings, stock, customer, supplier, and invoices. Do not use production financial records as fixtures.

1. **Cash sale:** create a multi-line sale; verify one sale, correct line snapshots, one stock movement per line, one successful payment, posted balanced sale/COGS journals, and no duplicate records on same-key replay.
2. **Credit sale and receipt:** create a credit sale; collect partial then remaining balance; verify customer balance and payment state after each step, one posted receipt journal per payment, and no over-collection.
3. **Return/refund:** request a partial return; reject over-return; approve and inspect; restock only approved quantities; execute refund once; verify stock, payment/refund records, customer reconciliation, and linked balanced reversal/return journals.
4. **Purchase receiving and supplier invoice:** create order with fractional quantities up to six decimals; partially receive; post supplier invoice; verify stock and payable journals reconcile to the same rounded totals.
5. **Supplier payment:** pay partially then fully; verify supplier obligation balance, evidence, method-specific cash/bank/clearing account, and exactly one posted balanced journal per payment.
6. **Expense voucher:** record expense; verify voucher and balanced posted journal; cancel with reason; verify original voucher remains, original journal is marked reversed, exactly one linked reversing journal is posted, and retry returns the same result.
7. **Negative/security cases:** changed-payload idempotency replay, insufficient balance, duplicate return, wrong tenant, inactive member, unauthorized role, missing account mapping, mismatched currency, malformed reason, network retry, and induced journal-posting failure must fail without partial stock/payment/ledger/voucher writes.
8. **Cross-ledger reconciliation:** for every successful scenario, assert journal debits equal credits by currency, source IDs and organization IDs match, source balances equal their ledger balances, and no posted source lacks its required journal.

Passing static contract tests is not a substitute for running this suite with authenticated clients against disposable Supabase and a successful Android build. Keep release status blocked until those results are attached to the PR.
