# Hospital–Finance Integration Contract (Phase 1)

## Purpose

The Hospital database is the source of truth for its operational invoices and received payments. The Finance domain remains the source of truth for Finance Payments, Settlements, FinancialTransactions, LedgerEntries, and balances. The hospital `finance_outbox` transports versioned handoff intents; it is not a second ledger.

## Implemented boundary

`hospital.finance_adapter.validate_hospital_finance_event` validates a claimed outbox row before any downstream mapping:

- accepts only `hospital.invoice.created` and `hospital.payment.received` v1 payload allowlists;
- verifies aggregate IDs and stable event keys against payload identities;
- validates positive identifiers, amount bounds, uppercase three-letter currency codes, and timezone-aware ISO-8601 timestamps;
- returns a minimal typed object and deliberately omits patient identifiers and raw payment references;
- rejects unknown event types, extra payload fields, malformed JSON, and inconsistent identity.

Validation means only that the transport envelope is well formed. It does **not** authorize, persist, settle, or recognize a financial transaction. A consumer must not mark an event delivered merely because validation passed.

## Required before durable Finance writes

1. **Explicit source correlation.** Hospital integer IDs must not be cast or guessed into Finance UUIDs. Persist a unique mapping between hospital source/event identities and Finance records, with conflict detection and replay protection.
2. **Party and account mapping.** Resolve active persisted Finance Persons and the correct active Financial Account from an approved configuration. Never infer parties from patient records or invent accounts.
3. **Obligation and settlement lifecycle.** An invoice event alone is not a Payment or Settlement. A received hospital payment must not be treated as a Finance Payment in the `completed` state or as a settled obligation without the required Finance lifecycle and evidence. The current orchestrator rejects partial settlement.
4. **Authorization and approval.** Use the existing Finance application APIs and their active operation-specific AuthorizationGrant, matching approved AgentAction, recorded human Approval, and connectivity checks. The outbox is not an authorization grant.
5. **Durable idempotency and audit.** Persist source correlation, consumer status, request fingerprint, and the outcome atomically with each Finance-side operation where the persistence model permits. Retries of the same event must not duplicate effects; conflicting reuse of a source key must be quarantined.
6. **Cross-database recovery.** Hospital and Finance are separate databases, so there is no single atomic transaction spanning both. Keep the outbox event retryable until Finance-side durable acceptance is confirmed; reconcile stale processing leases, duplicate deliveries, mapping failures, and unmatched amounts.
7. **No silent accounting assumptions.** Do not post a ledger entry directly from an invoice/payment event, use opening-balance flows to import live activity, or claim complete double-entry reporting until the chart of accounts, posting rules, and required reports are implemented and tested.

## Delivery state

This phase adds validation and contract tests only. No worker is enabled, no Finance records are created by this adapter, and no outbox events should be marked delivered by validation alone. The next implementation step is a durable source-correlation/consumer-intake table and reconciliation report, followed by a governed mapping workflow; recognition should be connected only after those invariants are enforced end to end.
