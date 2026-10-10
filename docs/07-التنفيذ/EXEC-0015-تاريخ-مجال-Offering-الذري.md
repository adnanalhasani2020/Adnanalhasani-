# EXEC-0015 — Atomic Offering domain history

## Functional gap
The persisted Offering lifecycle already supported create/activate/end/withdraw with optimistic version checks, but neither creation nor transitions wrote Domain History. A state change could therefore succeed without the corresponding history record.

## Implemented behavior
- Require a non-empty caller-supplied `actor_context_ref`; do not fabricate an actor.
- Persist creation (`draft`, version 1) and each lifecycle transition to `domain_history` in the same SQLite transaction as the Offering mutation.
- Record prior/current version references and the explicit action/state.
- Roll back the Offering insert/update if its history write fails.
- Preserve existing allowed transitions, optimistic concurrency, schema, and boundaries: no availability or inventory mutation is added.

## Traceability
- SPEC-0021: Domain History is distinct from Audit and Provenance; changes must not erase prior domain truth.
- SPEC-0005 §6: Offering is the commercial offer within an Activity; lifecycle remains Offering-owned.
- Existing implementation: `src/agent_core/offering_application.py`.
- Integration tests: `tests/test_stage22_offering_application.py` cover persistence, lifecycle history, required actor context, and atomic rollback.

## Security boundary
This internal persistence increment does not define or invent Offering authorization action codes. It accepts an explicit actor-context reference supplied by the caller; it is not a user-facing endpoint.
