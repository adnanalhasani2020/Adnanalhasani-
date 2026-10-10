# EXEC-0017 — Atomic InventoryPosition lifecycle history

## Functional gap
`InventoryPositionApplication` persisted an observed position and later lifecycle transitions, with optimistic version checks on transitions, but it did not write `domain_history`. State changes therefore lacked durable lifecycle history.

## Contract used
- Reuse the existing `actor_context_ref` caller-supplied, non-empty string contract already used by `ProductApplication` and `Offering` persistence.
- Do not synthesize an actor identity, authorization grant, or action code. This increment records caller-provided context; it does not add an authorization policy.
- Keep the existing transitions only: `observed → effective → closed`.

## Implemented behavior
- Creation writes the `inventory_positions` row and a version-1 `domain_history` entry in the same transaction.
- Each supported transition updates state/version with the existing compare-and-swap guard and inserts the corresponding history entry in that same transaction.
- History records `owner_domain=inventory`, target position, `inventory_position_lifecycle`, caller context, prior/current version references, and action/state payload.
- A history insert failure rolls back the position insert or state/version update.
- Existing inventory quantity values remain observations only. This work adds no quantity correction, reservation, sale movement, or financial effect.

## Verification
- `tests/test_stage21_inventory_position_application.py` verifies create/transition persistence, version-linked history after database reopen, missing actor-context rejection, and rollback when history insertion fails.
- GitHub Actions on the PR head and post-merge SHA are the authoritative CI evidence; do not infer success before runs complete.

## Traceability
- Inventory lifecycle: `SPEC-0006` (Inventory / Discovery domain boundary).
- History/provenance distinction and traceability: `SPEC-0021`.
- Implementation: `src/agent_core/application.py`, `InventoryPositionApplication`.
- Tests: `tests/test_stage21_inventory_position_application.py`.

## Boundary
This is lifecycle history persistence only. It does not settle inventory authority/conflict semantics reserved by Issue #102, alter Service lifecycle contract in Issue #105, or change PR #77 / PR #54. It does not create sales, reservations, quantity corrections, or financial records.
