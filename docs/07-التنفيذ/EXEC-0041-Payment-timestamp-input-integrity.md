# EXEC-0041 — Payment timestamp input integrity

**Status:** Implemented on an isolated branch; GitHub Actions is the required verification gate.

## Gap
The persisted Payment application used `now or datetime.now(...)` when choosing caller-supplied timestamps. That expression silently replaced explicit falsey inputs (including an empty string, `0`, and `False`) with the current time. Invalid caller input could therefore be accepted as a valid operation timestamp rather than rejected.

## Change
- Use the current UTC time only when `now is None`.
- Pass every explicitly supplied value to the existing timezone-aware ISO-8601 validator.
- Add regression tests for Payment creation and transition operations with falsey timestamp inputs.
- Assert that rejected inputs create no Payment or Payment Domain History records.

## Boundary
- No schema migration or new state transition.
- No changes to AuthorizationGrant, AgentAction, or human approval requirements.
- No Settlement, FinancialTransaction, LedgerEntry, or Balance effects.
- No receipt/voucher semantics are inferred; the open domain decision remains open.
- CI on the final PR head is required; this document does not claim a test run that has not occurred.
