# Customer-submitted payment requests

## Purpose

A customer who has already handed money to a merchant can submit a claim from the customer portal instead of relying on a busy cashier to remember the collection. The claim is a review request, not a receipt and not a payment.

## Customer flow

1. The signed-in customer opens the linked organization in the customer portal.
2. The portal lists that customer's completed invoices with an outstanding balance.
3. The customer selects one invoice, enters the amount actually handed to the merchant, and may add a note.
4. The request remains **pending** until an authorized merchant reviews it. The invoice balance and accounting ledger do not change at submission.
5. If the merchant asks for a correction, the request becomes **change_requested**. The customer can edit the amount/note and resubmit; the resubmission is audited and returns the request to **pending**.
6. The customer sees **rejected** or **approved** status and receives an in-app notification. An approved request links to the numbered receipt created by the existing collection workflow.

## Merchant flow

The customer-debt screen shows pending customer claims alongside outstanding receivables. A merchant can:

- Approve only when the amount entered as actually received exactly matches the customer's claim. Approval calls the existing `commercial_record_customer_payment` RPC, which validates the current outstanding amount and performs the existing payment/journal workflow atomically.
- Request a correction with a required explanation. This creates no payment.
- Reject with a required reason. This creates no payment.

The merchant chooses the payment method at approval. The customer cannot choose or self-approve a payment method.

## Integrity and security rules

- Requests are tied to one completed sale/invoice so the existing receivable subledger and payment posting remain authoritative.
- Customer RPCs validate the authenticated, verified-email-linked customer identity and ownership of the sale.
- Merchant list/review RPCs require an active organization owner, admin, or manager.
- Amounts are stored in currency minor units; both customer entry and merchant confirmation use readable major currency units and convert using the currency's configured fraction digits. Precision beyond the currency's supported minor unit is rejected.
- Pending requests reserve part of the invoice's currently unreserved balance so multiple claims cannot collectively exceed the debt.
- The amount and outstanding balance are revalidated during merchant approval; a changed or already-collected balance cannot be silently over-collected.
- Idempotency keys protect create, edit, and review retries. Request events preserve submission, resubmission, correction, rejection, and approval history.
- Request and event tables have RLS enabled and no direct client table grants. Access is through narrowly scoped authenticated RPCs.
- Approval is the only path that invokes the existing payment RPC. A customer submission, edit, request for changes, or rejection never inserts a payment or posts a journal.

## Deployment note

The original undeployed migration `20261103010000_customer_payment_confirmation_requests.sql` is retained under `supabase/migrations_archive/2026-11-06/` so it cannot be replayed out of order. The forward-only replacement is `supabase/migrations/20261106115128_customer_payment_confirmation_requests_forward_20261106.sql`. The production database was checked on 2026-11-06 and still does not contain the request tables. This branch adds a PostgreSQL 17 lifecycle/security smoke test; the migration must not be treated as deployed until the PR is merged, the reviewed migration is applied through the normal deployment process, and authenticated customer/merchant acceptance tests pass.
