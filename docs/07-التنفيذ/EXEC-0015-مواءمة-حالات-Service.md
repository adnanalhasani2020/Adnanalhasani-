# EXEC-0015 — Align Service state vocabulary with persistence

## Verified gap
At baseline `a2b1b70fd028817d71af1840bec26d46f48a640c`, `ServiceState` used `defined/active/inactive/retired` and `Service()` defaulted to `defined`, while the persisted `services.state` CHECK constraint accepts only `draft/active/retired`. This meant the domain's default Service state could not be persisted without an invalid-state failure.

## Change
- Align the enum vocabulary with the actual schema: `draft`, `active`, `retired`.
- Set the in-memory Service initial state to `draft`, which is valid in persistence.
- Add a regression test that compares the enum values against the actual SQLite table constraint and checks the default.

## Boundary
This does **not** define a Service lifecycle transition graph or authorization action codes. Those remain blocked by the decision request in Issue #105. Existing transitions and policies are not inferred from this vocabulary correction.

## Traceability
- `SPEC-0005`: Service is a supporting concept for Offering; the persisted state vocabulary is constrained by the existing schema.
- `SPEC-0003`: authorization remains a separate decision; no action codes are added.
- Tests: `tests/test_stage33_service_state_contract.py`.
