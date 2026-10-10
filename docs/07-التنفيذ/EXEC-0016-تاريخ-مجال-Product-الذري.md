# EXEC-0016 — Atomic Product domain history

## Functional gap
Product create/activate/retire persisted the current row and used optimistic versions, but did not write Domain History. The previous state/version sequence was therefore not durably traceable.

## Implemented behavior
- Require a non-empty caller-supplied `actor_context_ref`; do not invent actor identity.
- Persist Product creation at version 1 and each supported lifecycle transition in `domain_history` in the same transaction as the Product write.
- Record prior/current version references and action/state.
- Roll back the Product insert/update if the history insert fails.
- Keep the established `draft → active → retired` lifecycle and preserve linked Offering/Sale records.

## Traceability
- SPEC-0005 §5: Product lifecycle and retirement must not delete or reinterpret linked commerce history.
- SPEC-0021 §§5, 7, 8: Owner Domain retains truth; Domain History is distinct from Audit/Provenance and changes remain traceable.
- Implementation: `ProductApplication` in `src/agent_core/application.py`.
- Tests: `tests/test_stage22_product_application.py` cover lifecycle history, actor-context validation, and atomic rollback.

## Boundary
This internal persistence increment does not invent authorization action codes or create Offerings, inventory, Availability, Sale, Invoice, or financial effects.
