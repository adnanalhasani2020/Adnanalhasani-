# Hospital–Finance Integration Contract (Phase 2)

## Purpose

The Hospital database is the source of truth for its operational invoices and received payments. The Finance domain remains the source of truth for Finance Payments, Settlements, FinancialTransactions, LedgerEntries, and balances. The hospital `finance_outbox` transports versioned handoff intents; it is not a second ledger.

## Implemented boundaries

### Hospital event validation

`hospital.finance_adapter.validate_hospital_finance_event` validates a claimed outbox row before downstream mapping:

- accepts only `hospital.invoice.created` and `hospital.payment.received` v1 payload allowlists;
- verifies aggregate IDs and stable event keys against payload identities;
- validates identifiers, amounts, currency representation, and timezone-aware timestamps;
- returns a minimal typed object without patient identifiers or raw payment references;
- rejects unknown event types, extra payload fields, malformed JSON, and inconsistent identity.

Validation is not authorization, durable acceptance, settlement, or accounting recognition.

### Finance-side durable intake schema

Migration `0040_external_event_intake.sql` adds:

- `external_event_intake`, uniquely keyed by source system and source event key, storing a SHA-256 payload fingerprint, minimal financial metadata, intake status, and an optional explicit Finance target;
- `external_event_intake_conflicts`, recording a reused source key observed with a different payload fingerprint without copying the incoming payload;
- database constraints for supported event types, aggregate/event consistency, digest and currency representation, non-negative amounts, and valid mapped-state metadata.

This is a persistence contract, not yet a running consumer. No service currently writes these rows, compares incoming fingerprints, performs mapping, or marks Hospital outbox events delivered. Those behaviors must be implemented and tested before production delivery.

## Required before durable Finance writes

1. **Source correlation.** Hospital integer IDs must not be cast or guessed into Finance UUIDs. The consumer must compare fingerprints for duplicate event keys; identical retries are idempotent, while conflicting reuse is recorded and quarantined.
2. **Party and account mapping.** Resolve active persisted Finance Persons and the correct active Financial Account from approved configuration. Never infer parties from patient records or invent accounts.
3. **Obligation and settlement lifecycle.** An invoice event alone is not a Payment or Settlement. A received Hospital payment must not be treated as a completed Finance Payment or settled obligation without the required Finance lifecycle and evidence. The current orchestrator rejects partial settlement.
4. **Authorization and approval.** Use existing Finance application APIs and their active operation-specific AuthorizationGrant, matching approved AgentAction, recorded human Approval, and connectivity checks. The outbox is not authorization.
5. **Atomic durable outcomes.** Persist source correlation, consumer status, request fingerprint, and the outcome atomically with each Finance-side operation where the persistence model permits. Do not mark an event delivered merely because it passed validation.
6. **Cross-database recovery.** Hospital and Finance are separate databases. Keep events retryable until Finance-side durable acceptance is confirmed; reconcile stale leases, duplicates, mapping failures, and unmatched amounts.
7. **Accounting integrity.** Do not post a ledger entry directly from an invoice/payment event, use opening-balance flows to import live activity, or claim complete double-entry reporting until posting rules, chart of accounts, and required reports are implemented and tested.

## Delivery state

Validation and the Finance intake schema are present, with tests. No worker is enabled, no Finance records are created by the adapter, and no event should be marked delivered solely after validation or intake. Next: implement a small durable intake service with fingerprint conflict handling and a read-only reconciliation report, then connect governed mappings and Finance lifecycle operations only after their invariants are enforced end to end.
