# Commerce Vertical Slice — Readiness and Execution Plan

**Date:** 2026-10-17  
**Baseline:** `main @ 1f772c90688274b8191007434ec640a4339d6db6`  
**Status:** Planning evidence only; no requirement is accepted and no implementation stage is opened by this document.

## Objective

Deliver one small, testable commerce journey before expanding the product surface:

1. Resolve a valid active Offering in its Activity/Product context.
2. Read inventory in the exact intended scope.
3. Create and transition a Sale using existing authorization and lifecycle contracts.
4. Verify that persistence and domain history remain consistent after database reopen.
5. Fail closed when inventory is unknown, ambiguous, stale for the requested instant, or inconsistent with its Offering context.

This is an acceptance target, not a claim that the complete journey currently works.

## Evidence in the current repository

- Product, Offering, Sale, and InventoryPosition domain/application code exists.
- SQLite persistence and migrations exist, with tests for Sale lifecycle, InventoryPosition lifecycle, and inventory quantity reads.
- Inventory quantity reads are read-only. They do not reserve or deduct quantity.
- Payment records do not prove external money movement; no operational bank, wallet, or payment-gateway integration was evidenced in the reviewed repository.
- The repository documents that a full Web/Mobile UI and a public operational API are not present in the current implementation.

## Required blockers before implementation

### 1. Inventory quantity correction — Issue #102

Do not add a quantity mutation path until the contract is approved. The open decision must define the existing authorization action code, grant scope/context matching, whether an approved persisted AgentAction is required, actor context, expected-version/CAS behavior, atomic history, and negative-quantity policy. No reservation, sale movement, or financial side effect should be inferred.

### 2. Service lifecycle — Issue #105

Do not invent Service transitions or authorization codes. Existing Sale behavior requiring an active linked Service remains the safe boundary until the lifecycle and authorization contract is decided.

### 3. Sale terminal-state semantics

The repository records an unresolved semantic mismatch between `fulfilled` in SPEC-0005 and `completed` in current domain/schema behavior. Do not silently treat the terms as synonyms. Obtain an authoritative decision before changing domain or schema semantics.

### 4. Availability and reservation semantics

Inventory quantity and commercial availability are not interchangeable. Before implementing reservations or a final purchasability decision, approve the ownership, scope, temporal/freshness, concurrency, expiry, release, and conflict behavior. Do not infer these policies from the existing read interface.

## Proposed implementation sequence

### Slice A — Read-only commerce acceptance (safe first increment)

- Build on the existing Product/Offering/Sale and inventory read contracts.
- Add an integration acceptance test that composes existing supported operations without introducing a new stock mutation or financial side effect.
- Include unknown quantity, ambiguous latest observations, mismatched Offering context, inactive Offering/Service, unauthorized Sale transition, and database-reopen cases.
- Assert rejected operations leave persisted state and history unchanged.

**Exit criteria:** the integrated test passes on the exact PR head in both required workflows, and it does not claim reservation, stock deduction, external payment, or full UI readiness.

### Slice B — Approved stock mutation contract

Only after Issue #102 and relevant availability/reservation decisions are resolved: implement the smallest authorized quantity-correction or stock-movement contract, with compare-and-swap, atomic history, and regression tests. Do not bundle reservations or Sale side effects unless explicitly approved.

### Slice C — User-facing operational entry point

Once the service contracts are stable and scope is approved, define and implement the minimum operational API/UI for the accepted journey. Do not treat this plan as authorization to open a new stage or bypass repository governance.

### Slice D — External payment integration

Treat as a separate workstream with provider selection, credentials/secrets handling, callback authenticity, idempotency, reconciliation, failure handling, and explicit financial authority. A locally stored Payment record is not evidence of external payment completion.

## Verification checklist for the next implementation PR

- [ ] Exact base/head SHAs recorded.
- [ ] Full diff reviewed against current main.
- [ ] Python tests pass on the exact final head.
- [ ] Stage 7 Automated Tests pass on the exact final head.
- [ ] Rejected operations produce no partial writes or history.
- [ ] Database-reopen assertions cover the accepted path.
- [ ] No unresolved policy is silently encoded.
- [ ] No claim of production readiness or full commerce completion is made.

## Decision

The next safe engineering increment is a read-only, end-to-end acceptance test over already-supported behavior. Quantity correction, reservation, automatic stock deduction, unresolved Sale-state semantics, and external money movement remain blocked until their contracts and authority are explicit.
