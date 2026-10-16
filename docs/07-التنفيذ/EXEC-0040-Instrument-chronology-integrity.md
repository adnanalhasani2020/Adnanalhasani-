# EXEC-0040 — Instrument chronology integrity

## Purpose
Close a persistence-integrity gap in the existing SPEC-0016 Instrument lifecycle: a caller could supply a transition timestamp earlier than the Instrument's creation or most recent update.

## Change
- Validate each transition timestamp against both persisted `created_at` and `updated_at` using timezone-aware UTC datetimes.
- Reject explicitly supplied falsey timestamp values rather than silently substituting the current time.
- Reject backdated transitions before any Instrument, usage, or Domain History write.
- Fail closed if persisted lifecycle timestamps are malformed or timezone-naive.
- Preserve existing optimistic version checks and caller-owned transaction/savepoint behavior.

## Tests
`tests/test_stage31_instrument_application.py` covers timestamps before creation, timestamps before the latest transition, malformed persisted timestamps, falsey explicit timestamps, and unchanged state/version/history after rejection.

## Traceability
- SPEC-0016 §§8–10: lifecycle state, failure boundaries, and historical preservation.
- Existing implementation: `src/agent_core/instrument_application.py`.
- Existing relational schema reused; no migration.

## Explicit boundaries
This is an integrity fix to the existing generic Instrument lifecycle. It does not define cash-receipt/payment-voucher semantics, create new Instrument subtypes, create Payment/Settlement/Ledger effects, add authorization codes, expose a user-facing endpoint, or authorize Stage 12B. Those would require the project's approved domain/authorization contracts.

## Verification
GitHub Actions on the final pull-request head is the verification gate. No pass is claimed until it completes.
