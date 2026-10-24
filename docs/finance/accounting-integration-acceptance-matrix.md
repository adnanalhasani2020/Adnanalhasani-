# Accounting Integration Acceptance Matrix

**Status:** Acceptance specification; not evidence that the workflows currently pass.
**Scope:** Verify the existing commercial document, inventory, payment, and journal components without creating a second ledger or changing production data.

## Release rule

Do not mark a workflow complete based on a screen, table, RPC name, or schema-only test. A workflow is accepted only when a disposable local/staging database and an authenticated application session prove the full path from source document through stock/party effects to a balanced posted journal and read-only report. Record the commit SHA, migration set, test command, and result.

## Preconditions to confirm before writing workflow tests

- Read the current migrations and exact RPC signatures for checkout, payments/allocations, returns, inventory, and journal posting.
- Confirm the canonical organization/activity scope and the authorization predicate for each operation.
- Confirm the supported currency and accounting-date rules; do not mix currency balances.
- Confirm whether inventory cost is recorded and which costing policy is implemented. Do not assume a cost-of-goods-sold entry exists because inventory is decremented.
- Use only disposable seeded records. Never repair or mutate production financial history as part of a test.

## Acceptance matrix

| ID | Scenario | Required assertions | Failure conditions |
|---|---|---|---|
| ACC-01 | Post a balanced journal | Sum of debit minor units equals sum of credit minor units per currency; all accounts belong to the same authorized scope and are active/postable; source reference is retained. | Unbalanced entry posts; cross-currency totals are netted; invalid/inactive account posts. |
| ACC-02 | Reject an unbalanced journal | Posting is rejected atomically; entry is not left in a posted state; no partial lines or side effects remain. | Entry posts or partial writes survive. |
| ACC-03 | Retry a source operation | Repeating the same idempotency key and same request returns/references the same effect; no duplicate sale, payment, stock movement, or journal. | Any financial or stock effect duplicates. |
| ACC-04 | Reuse key with changed payload | A key bound to a different request fingerprint is rejected without altering the original record. | The second payload silently reuses or overwrites the first operation. |
| ACC-05 | Posted journal immutability | Posted lines cannot be updated/deleted through direct SQL or application APIs; authorized correction uses a traceable reversal/correction workflow. | Original posted history is changed or erased. |
| ACC-06 | Cash sale | Server-confirmed sale, item snapshots, payment state, inventory movement, and journal effects agree; any unsupported accounting mapping blocks posting explicitly. | UI claims success without server confirmation; stock/payment/journal is partial or duplicated. |
| ACC-07 | Credit sale / customer debt | Do not enable until a supported cloud workflow and authoritative outstanding-balance model exist. When implemented, verify invoice-to-receivable link and partial allocation. | Local-only debt is represented as cloud-confirmed or authoritative. |
| ACC-08 | Customer receipt allocation | Allocation cannot exceed the receipt or obligation balance, cannot cross currencies or organizations, and retries are idempotent. Remaining balance is derived from persisted allocations. | Over-allocation, cross-scope allocation, or manually drifted balance. |
| ACC-09 | Purchase and receiving | Accepted receipt changes stock exactly once; supplier invoice/payment and journal effects reference the same source chain. Partial receipts are supported only if modeled. | Purchase order alone is treated as received stock or supplier liability. |
| ACC-10 | Sales return | Return references original sale lines; cumulative returned quantity and refund cannot exceed eligible amounts; approval, inspection, refund, restock, and reversal remain traceable and idempotent. | Original invoice is edited; duplicate restock/refund; unapproved return affects stock or money. |
| ACC-11 | Organization isolation | User A cannot read, create, update, post, allocate, or reverse records for organization B without an explicit authorized membership/permission. Test both RLS and RPC paths. | Any cross-organization access or write succeeds. |
| ACC-12 | Permission boundaries | Roles are tested for allowed and denied actions, including viewing balances, managing catalog data, posting journals, refunds, and reversals. | UI hiding is the only protection; unauthorized RPC succeeds. |
| ACC-13 | Concurrent retries | Concurrent identical requests result in one canonical effect; competing allocations/returns cannot over-consume the same balance/quantity. | Race condition creates duplicate effects or negative remainder. |
| ACC-14 | Trial balance fixture | Once the canonical posted-ledger query is approved, report totals equal hand-calculated expected totals and debits equal credits for each currency and period. | Report reads operational account activity as a substitute for posted journals or totals disagree. |
| ACC-15 | Income statement and balance sheet | Build only after ACC-01..05 and account classification/period policies are approved; verify a hand-calculated fixture, retained earnings policy, and cross-report reconciliation. | Unmapped accounts silently disappear or are guessed into categories. |
| ACC-16 | Audit trail | Actor, organization, source, operation/request ID, timestamps, and reversal lineage are queryable for relevant state changes; direct authenticated insert/update/delete of append-only audit events is denied. | Material changes cannot be attributed or audit history can be rewritten. |

## Hand-calculated starter fixture

Use a disposable organization, one currency, no tax, no discounts, no debt, no additional charges, and an explicitly configured inventory costing method:

- Opening cash: 100,000 minor units.
- Buy inventory for cash: 30,000.
- Sell inventory for cash: 20,000 revenue.
- Cost of goods sold for that sale: 12,000.
- Pay operating expense in cash: 2,000.

Expected balances after all operations are correctly posted:

| Measure | Expected |
|---|---:|
| Cash | 88,000 |
| Revenue | 20,000 |
| Cost of goods sold | 12,000 |
| Operating expense | 2,000 |
| Net income | 6,000 |
| Remaining inventory at cost | 18,000 |

Expected accounting effects (account names are placeholders; tests must use the approved chart mapping):

1. Opening cash: debit cash 100,000 / credit approved opening-equity account 100,000.
2. Inventory purchase: debit inventory 30,000 / credit cash 30,000.
3. Sale: debit cash 20,000 / credit revenue 20,000.
4. Cost recognition: debit cost of goods sold 12,000 / credit inventory 12,000.
5. Expense: debit operating expense 2,000 / credit cash 2,000.

Do not run this fixture until the current posting contract can represent the approved source references, accounting dates, currencies, and reversal lineage. If any of these effects are not supported, record the exact missing prerequisite rather than manufacturing test rows that bypass the workflow.

## Evidence template

For each scenario, record:

- Commit SHA and migration version.
- Database target (local or staging; never production for destructive tests).
- Exact test command and exit status.
- Authenticated role/organization used.
- Source document ID and idempotency key (test data only).
- Before/after stock, payment allocation, journal, and account balances.
- Expected versus actual result.
- Failure details and linked follow-up issue.

## Current boundary

The existing commercial_integrity_test.sql is a schema/security regression suite and explicitly does not replace authenticated-session integration tests. Passing that file alone does not establish that journal balancing, sale-to-journal posting, debt allocation, or financial reports work end to end. Keep trial balance, income statement, balance sheet, cash-flow, aging, bank reconciliation, and consolidated FX behind their respective dependency and policy gates.
