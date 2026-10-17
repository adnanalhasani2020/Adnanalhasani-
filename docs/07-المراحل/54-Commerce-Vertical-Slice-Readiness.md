# Commerce Vertical Slice — Readiness and Execution Plan

**Date:** 2026-10-17  
**Baseline:** `main @ 0c41252e6dce02a2c1edd85ba272f37cb88243d7`  
**Status:** Planning and evidence only; no requirement is accepted and no implementation stage is opened by this document.

## Objective

Deliver one small, testable commerce journey before expanding the product surface:

1. Resolve a valid active Offering in its Activity/Product context.
2. Read inventory in the exact intended scope.
3. Create and transition a Sale using existing authorization and lifecycle contracts.
4. Verify persistence and domain history after database reopen.
5. Return explicit fail-closed inventory statuses for unknown, ambiguous, invalid, stale, or context-inconsistent records.

This is an acceptance target, not a claim that the complete journey currently works.

## Evidence verified on main

- Product, Offering, Sale, InventoryPosition, and SQLite persistence/migrations exist.
- `tests/test_stage34_commerce_sale_inventory_integration.py` already composes Product → Offering → inventory reads → authorized Sale lifecycle. It verifies exact Activity/Offering stock isolation, authorization scope rejection, rollback when history persistence fails, no stock deduction on Sale confirmation, and database-reopen history integrity.
- Inventory quantity unit tests cover unknown quantity (NULL is not zero and does not fall back), ambiguous tied latest observations, time boundaries, invalid persisted quantities, and integer persistence guards.
- `InventoryApplication` is read-only. It does not reserve or deduct quantity. `InventoryPositionApplication` has lifecycle and opening-stock operations, but no general quantity-correction operation.
- Opening-stock creation is separately guarded: it requires a non-negative integer and idempotency data. That rule must not be silently generalized to all quantity-correction semantics while Issue #102 remains open.
- Payment records do not prove external money movement; no operational bank, wallet, or payment-gateway integration is evidenced in the reviewed implementation.
- The reviewed repository does not provide a complete operational Web/Mobile UI or public operational API.

## Inventory review: what is safe to correct now

- Reject malformed/non-integer persisted quantities through the existing persistence guard; the reader reports legacy invalid values as `INVALID_QUANTITY` instead of exposing them as usable stock.
- Keep NULL quantity as `UNKNOWN_QUANTITY`, not zero; tied latest observations remain `AMBIGUOUS`; do not aggregate different scopes or borrow quantity from another Offering/Activity.
- Do not manually rewrite stored quantities or introduce a correction API without a source count and an approved correction contract. The repository is code, not the live inventory ledger; no live database/count evidence was supplied here.
- Do not infer that a known quantity means the item is purchasable, reserved, or safe to deduct.

## Required blockers before stock mutation or wider commerce implementation

### 1. Inventory quantity correction — Issue #102

Do not add a general quantity mutation path until the contract is approved. It must define the authorization action code, grant scope/context matching, whether a persisted approved AgentAction is required, actor context, expected-version/CAS behavior, atomic history, and negative-quantity policy. No reservation, sale movement, or financial side effect should be inferred.

### 2. Service lifecycle — Issue #105

Do not invent Service transitions or authorization codes. Existing Sale behavior requiring an active linked Service remains the safe boundary until the lifecycle and authorization contract is decided.

### 3. Sale terminal-state semantics

The repository records a semantic mismatch between `fulfilled` in SPEC-0005 and `completed` in current domain/schema behavior. Do not silently treat the terms as synonyms. Obtain an authoritative decision before changing domain or schema semantics.

### 4. Availability and reservation semantics

Inventory quantity and commercial availability are not interchangeable. Before implementing reservations or a final purchasability decision, approve ownership, scope, temporal/freshness, concurrency, expiry, release, and conflict behavior.

## Next implementation sequence

### Slice A — Close the remaining read-only acceptance gaps

Extend the existing `test_stage34_commerce_sale_inventory_integration.py` rather than creating a duplicate integration harness. Cover inventory status outcomes in the same Offering/Activity graph (unknown quantity and tied latest observations), context-integrity failure, and unchanged inventory/history on rejected operations. Keep assertions aligned with the current contract: Sale creation is not blocked by stock status unless an approved rule explicitly says so.

**Exit criteria:** Python tests and the Stage 7 workflow pass on the exact final PR head; the test claims no reservation, deduction, external payment, or full UI readiness.

### Slice B — Approved stock mutation contract

Only after Issue #102 and relevant availability/reservation decisions are resolved: implement the smallest authorized correction contract with compare-and-swap, atomic history, and regression tests. Do not bundle reservations or Sale side effects unless explicitly approved.

### Slice C — User-facing operational entry point

Once service contracts are stable and scope is approved, define and implement the minimum operational API/UI for the accepted journey. This plan does not authorize opening a stage or bypassing repository governance.

### Slice D — External payment integration

Treat this separately with provider selection, secrets handling, callback authenticity, idempotency, reconciliation, failure handling, and explicit financial authority. A locally stored Payment record is not evidence of external payment completion.

## Merge and validation checklist

- [x] Exact base/head SHAs reviewed for the superseded PR #267.
- [x] Documentation-only change; no runtime code, schema, migrations, or tests changed.
- [ ] Any subsequent code/test PR must pass both workflows on its exact final head.
- [ ] Rejected operations produce no partial writes or history.
- [ ] Database-reopen assertions cover the accepted path.
- [ ] No unresolved policy is silently encoded.
- [ ] No claim of production readiness or full commerce completion is made.

## Decision

The existing read-only commerce integration test is a useful foundation; the next code increment should extend its edge-case coverage rather than duplicate it. Quantity correction, reservation, automatic stock deduction, unresolved Sale-state semantics, and external money movement remain blocked until their contracts and authority are explicit.
