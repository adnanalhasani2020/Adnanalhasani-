# Purchases / Procurement — Contract Proposal (Not Approved)

**Status:** Preparation proposal only. This file does not approve a business policy or authorize production behavior.  
**Target branch:** `main`  
**Scope intent:** Prepare a procurement capability for the Commerce + Daily Services priority without duplicating Sale, Offering, Inventory, Invoice, Payment, or Settlement.

## 1. Repository audit

Reviewed the repository's documented state and the relevant existing implementation paths:

- `src/agent_core/domain_commerce.py`: Sale and Invoice domain models.
- `src/agent_core/domain_inventory.py`: Product, Service, Offering, InventoryPosition, and Availability models.
- `src/agent_core/application.py`: Sale lifecycle and read-only inventory quantity entry points.
- `src/agent_core/offering_application.py`: persisted Offering lifecycle.
- `src/agent_core/inventory_read.py` and `src/agent_core/commerce_inventory_read.py`: read-only inventory/context projections.
- `src/agent_core/invoice_application.py`, `payment_application.py`, and `obligation_application.py`: existing invoice/payment/obligation boundaries.
- `schema/migrations/0001_initial_relational_schema.sql` and `0007_product_name.sql`: existing relational schema and additive Product-name migration.
- `docs/06-المواصفات/SPEC-0006-المخزون-والتوافر.md` and `docs/20-الحالة-الفعلية-للبرنامج.md`: inventory ownership, scope limits, and current product status.

At the time of the original proposal audit, no dedicated purchase/procurement runtime module or purchase tables were found in the inspected `main` paths. The current inventory read path is explicitly read-only. Sale creation and lifecycle changes do not automatically deduct or reserve inventory; Payment/Invoice/Obligation are separate records with separate contracts.

**Updated review note (2026-10-14):** PR #192 now proposes an unmerged purchase lifecycle and supplier-invoice implementation on `feature/purchases-domain-v1`. It adds `domain_purchases.py`, `purchase_application.py`, `supplier_invoice_application.py`, migrations `0008_purchases.sql` and `0009_supplier_invoices.sql`, and focused tests. The draft reports receipt as evidence only and does not post inventory or financial effects. Its latest inspected head `4de58d019180ff4bfabb8b0915b62d5eb63550d6` has a successful Stage 7 workflow run, but passing tests do not approve unresolved business policy. Keep PR #192 unmerged until the decision points in section 3 are explicitly resolved and its full diff is reviewed against current `main`.

## 2. Required separation of concepts

A procurement capability must not reuse or rename existing records:

- **Purchase / Purchase Order**: a buyer-side procurement commitment, if that is the intended meaning.
- **Sale**: the existing customer-side commercial transaction.
- **Offering**: the published product/service offer in an Activity context.
- **Inventory Position**: the inventory-owned quantity/state fact.
- **Invoice, Payment, Obligation, Settlement, Ledger**: separate records with their existing ownership and rules.

A procurement write must not silently create a Sale, Payment, Settlement, FinancialTransaction, LedgerEntry, or inventory quantity change. Any receipt-to-inventory flow must be an explicit, separately specified integration with Inventory ownership and history preserved.

## 3. Decisions required before runtime implementation is approved

These are unresolved requirements, not defaults to infer in code:

1. **Meaning and boundary:** Is “Purchases” supplier procurement/purchase orders, recording completed purchases, or both?
2. **Supplier identity:** May a supplier be a persisted Person, an Organization, or must a dedicated Supplier concept be introduced? Do not infer supplier status from a role or membership.
3. **Line semantics:** Which fields are required (Product, quantity and unit, unit cost, currency, tax/discount, delivery location)? Existing `quantity_minor` is not automatically a universal purchase-unit contract.
4. **Lifecycle:** Which states and transitions are valid (for example draft, submitted, approved, ordered, partially received, received, cancelled)? Approval thresholds and who may perform them must be explicitly decided.
5. **Receipt and inventory:** Does receipt merely record evidence, or should it request a separate Inventory Position update? Never mutate inventory implicitly.
6. **Supplier invoice and finance boundary:** Is a supplier invoice only captured as a supplier document, or should a separately authorized workflow create/link an Obligation? Define treatment of tax, freight, credit notes, voiding, and currency conversion. No automatic financial effect by default.
7. **Authorization:** Define action codes, scope, context, actor type, grant validity, approval/delegation requirements, and audit payload for each write action.
8. **Idempotency and retries:** Define operation scope/key and payload-conflict behavior for create/submit/receive/invoice actions; do not claim external effects are exactly-once.
9. **Corrections and cancellations:** Define how mistakes are corrected without deleting history or rewriting past receipt facts.
10. **Price variance:** Decide whether variance is report-only (as in PR #192) or requires a separate review/approval workflow. Never infer approval from the recorded invoice price.

## 4. Proposed implementation boundary (pending decisions)

Once the decisions above are approved, implement in a dedicated module and additive migrations, rather than expanding the existing Sale class or creating duplicate helpers:

- One canonical domain module for procurement entities and lifecycle validation.
- One application module for persisted procurement operations, using the repository's transaction/savepoint pattern and the approved authorization contract.
- Additive migrations with foreign keys, state constraints, version fields, and explicit uniqueness rules. Do not edit historical migrations.
- Domain-history rows written atomically with each persisted state change.
- Tests for happy paths, invalid transitions, wrong actor/action/scope/context, expired/revoked grants, rollback with caller-owned transactions, concurrent updates, persistence reopen, duplicate requests, foreign-key integrity, and mismatched supplier/product/activity references.
- Traceability/documentation updates after the implementation contract is approved.

PR #192 already implements a substantial subset of this proposed boundary. Do not create a second implementation or duplicate its tables/tests while it remains under review. Reconcile the decision record and implementation plan first; then either approve the contract and continue that branch, or revise the implementation to match the approved contract.

## 5. Conflict and duplication prevention gate

Before creating or merging runtime files or migration tables:

1. Re-read the latest `main` and compare the candidate branch with it.
2. Search the full current tree for existing purchase-related names, tables, migrations, enums, tests, and traceability IDs.
3. Reuse the established transaction, UUID/timestamp validation, authorization, and domain-history conventions only where their semantics match; do not copy-paste a second shared helper.
4. Use one canonical purchase vocabulary and unique traceability identifier.
5. Run focused tests and the full CI suite on the final PR head SHA.
6. Review the final diff against latest `main`; merge only if the change is conflict-free, tests pass, and the required business decisions are approved.

## 6. Current completion status

- Repository/code/documentation reconnaissance: performed for the paths listed above.
- `main` currently has no dedicated purchase/procurement runtime module or purchase migrations in the inspected paths.
- PR #191 is a documentation-only proposal; this document does not approve the business contract.
- PR #192 is an unmerged implementation draft; its inspected head has successful Stage 7 CI, but unresolved business decisions remain a merge blocker.
- Runtime code or database migration: intentionally not added by this proposal-only change.
- Business contract: pending authorized decisions listed in section 3.

This proposal is a guardrail against inventing policy or colliding with existing Commerce/Inventory/Finance ownership. It is not evidence that procurement is production-ready.