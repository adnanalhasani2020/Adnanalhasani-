# EXEC-0014 — Read Instrument usage history

## Purpose
Deliver the missing read path for the usage history already persisted by the SPEC-0016 Instrument lifecycle.

## Contract and boundaries
- `InstrumentApplication.list_instrument_usages(connection, instrument_id)` returns immutable usage records for one existing Instrument, ordered oldest-first with stable tie-breakers.
- The read is scoped in SQL to the requested Instrument and validates that the Instrument exists.
- It returns only the existing operation/context references and stored usage metadata. It does not interpret opaque references or infer a Sale, Payment, Settlement, discount, or ledger effect.
- This remains an internal persistence primitive, not a user-facing endpoint: SPEC-0016 does not define Instrument-specific authorization action codes. No authorization code or policy is invented.

## Traceability
- REQ-FUNC-0041 → SPEC-0016 §§7, 10 and AC-03 (usage/context/result must be traceable).
- Implementation: `src/agent_core/instrument_application.py` (`InstrumentUsageRecord` and `list_instrument_usages`).
- Integration evidence: `tests/test_stage31_instrument_application.py` covers persisted redemption after database reopen, per-Instrument isolation, empty history, and unknown-Instrument rejection.

## Verification boundary
GitHub Actions on the pull request Head SHA is the authoritative CI gate. This increment does not change the schema, lifecycle rules, authorization policy, or financial state.
