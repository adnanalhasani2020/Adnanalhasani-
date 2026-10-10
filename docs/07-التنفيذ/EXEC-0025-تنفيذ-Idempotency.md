# EXEC-0025 — Durable idempotent application execution

## Scope
Adds a reusable SQLite application primitive keyed by operation scope and caller-provided idempotency key. The canonical JSON request fingerprint distinguishes a true retry from accidental key reuse. The stored JSON result is returned for an identical retry without calling the operation again.

## Transaction and failure contract
- The primitive owns a `BEGIN IMMEDIATE` transaction on a connection with no transaction already open.
- The callback must perform all database effects using that same connection.
- The operation effects and receipt commit atomically; an exception rolls both back so the request can be retried.
- SQLite writer serialization makes simultaneous requests for the same key deterministic.
- Receipts persist in the database and remain available after a connection is reopened.
- Callback results and requests must be JSON-compatible.

## Boundaries
This primitive does not automatically wrap existing domain operations, define a new commercial/financial contract, or create Payment, Settlement, FinancialTransaction, or LedgerEntry records. It does not make external network calls or non-database side effects exactly-once. Callers must opt in at a suitable application boundary and keep all atomic effects in the supplied transaction.

## Implementation and tests
- Implementation: `src/agent_core/idempotency.py`
- Tests: `tests/test_idempotency.py`
- Coverage: first execution, identical replay, changed payload conflict, scope isolation, connection reopen, rollback and retry, concurrent same-key requests.
- Validation: GitHub Actions CI on the final PR head SHA is the authoritative repository test gate.

## Traceability
Operational reliability / duplicate-request handling → `execute_idempotently` → `tests/test_idempotency.py`.
This is an opt-in primitive, not a claim that all domain entry points are already protected.
