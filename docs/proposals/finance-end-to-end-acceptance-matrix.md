# Finance End-to-End Acceptance Matrix

## Status and boundary

This document is a verification plan for issue #220, not an approval of financial policy and not evidence that the scenarios already pass. It introduces no schema, posting rule, currency rule, settlement cardinality, or money movement. Execute it only against an isolated test database with synthetic data.

Do not treat the manager settings prototype or a successful unit test as authorization to activate unresolved financial behavior.

## Required scenario matrix

| Step | Setup/action | Required assertion | Evidence |
|---|---|---|---|
| 1. Opening balance | Create an authorized test account and a valid opening-balance record using the currently approved contract | The stored amount, account identity, currency representation, and provenance match the input; invalid account ownership is rejected | Test name, DB state before/after |
| 2. Recognition | Submit one valid financial transaction linked to the intended account and obligation under the existing approved contract | Exactly one recognized transaction exists; no unrelated account or obligation is accepted | Transaction identifier and assertions |
| 3. Ledger posting | Post through the existing supported path | Posted entries reference the correct transaction and account; cross-account references fail closed | Ledger rows and rejection test |
| 4. Balance read | Read the account balance through the supported query path | Balance derives from the authoritative persisted sources; no report/read operation repairs or rewrites history | Before/after snapshots |
| 5. Report consistency | Generate the activity/ledger report for the relevant period | Report totals and detail agree with persisted entries and the balance read; corrupted ownership is rejected | Report output and consistency assertions |
| 6. Currency consistency | Exercise only currencies and precision already approved by the project | Transaction, ledger, settlement, and report currency fields remain consistent; unsupported or inconsistent representations are rejected | Approved currency fixture and assertions |
| 7. Retry/idempotency | Repeat the same operation using the same durable operation identity, then simulate a distinct operation identity for the same business object where relevant | Retry does not duplicate recognized or posted effects; conflicts with existing reversal/correction guards are rejected without partial effects | Counts and state snapshots |
| 8. Database reopen | Close and reopen the test database after successful posting | All recognized transactions, ledger entries, opening-balance history, and report results persist and remain consistent | Reopened DB query results |
| 9. Rejection atomicity | Attempt invalid ownership, invalid currency, duplicate/reversal conflict, and other currently specified rejection cases | Rejection leaves no partial transaction, ledger entry, history rewrite, or unrelated mutation | Before/after DB snapshots |
| 10. Historical integrity | Query records predating the requested report period, including malformed cross-account references | The report fails closed where required and does not silently rewrite historical rows | Exact corrupt fixture and unchanged-row assertion |

## Execution rules

1. Record the base SHA, tested head SHA, database engine/version, migration starting point, and exact commands used.
2. Run the Python Tests and Stage 7 Automated Tests on the exact final PR head. A result from an earlier head does not count.
3. For schema changes, upgrade a populated database from the supported prior schema; a fresh-database-only migration run is insufficient.
4. Capture state snapshots around every rejected operation and compare persisted rows, counts, history, and ledger effects.
5. Keep test fixtures synthetic. Do not include patient data, real account numbers, credentials, or production financial records.
6. If a requirement has no approved contract, mark that row **BLOCKED — DECISION REQUIRED** rather than choosing a convenient behavior in the test.

## Explicit blockers that this matrix does not resolve

- Q-0023: supported currencies, organization/default currency, minor-unit precision, and FX conversion.
- Q-0031: voucher cancellation contract.
- Issue #93: settlement cardinality for partial/repeated settlements.
- Account-code to ledger mapping and ownership of any chart-of-accounts persistence.
- Migration upgrade proof on a populated database for any schema-changing currency candidate.
- Reconciliation of diverged/closed PR work: passing tests on an old or diverged branch does not authorize a merge.

## Completion record

Do not mark issue #220 complete until each applicable row has recorded passing evidence, every unresolved policy row is explicitly blocked, and the required workflows pass on the exact final candidate. Unit tests for cross-account corruption are useful but do not replace this end-to-end sequence.
