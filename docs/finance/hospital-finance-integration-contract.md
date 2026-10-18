# Hospital–Finance Integration Contract (Phase 3)

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

### Payment request retries

`POST /api/payments` accepts an optional `idempotency_key` for client retries. A caller integrating Hospital with Finance must provide a stable, unique key per intended payment operation and reuse that same key only for retries of the exact same request. Hospital returns the original payment ID for a matching retry and rejects a reused key when the normalized request fingerprint differs. The key is persisted with a unique constraint; existing databases are upgraded in place. Legacy requests without a key remain accepted for compatibility but are not protected against duplicate submissions. This protects the Hospital payment write and its audit/outbox side effects; it does not itself create Finance records or prove downstream delivery.

### Finance-side durable intake schema

Migration `0040_external_event_intake.sql` adds:

- `external_event_intake`, uniquely keyed by source system and source event key, storing a SHA-256 payload fingerprint, minimal financial metadata, intake status, and an optional explicit Finance target;
- `external_event_intake_conflicts`, recording a reused source key observed with a different payload fingerprint without copying the incoming payload;
- database constraints for supported event types, aggregate/event consistency, digest and currency representation, non-negative amounts, and valid mapped-state metadata.

The schema is used by `agent_core.external_event_intake.ExternalEventIntake` for validated events. It inserts new source identities, treats matching fingerprints as idempotent retries, and records a conflicting fingerprint without overwriting the original facts. Unmapped conflicts become quarantined; a conflict after mapping preserves the established Finance target and is surfaced for manual reconciliation.

### Intake service and read-only report

`ExternalEventIntake.ingest` uses a savepoint so an intake insert or collision record and its status update succeed or roll back together. It fingerprints a canonical, minimized representation of the validated event and never stores raw payload JSON or raw payment references. Matching retries only update `last_seen_at`; they do not reset a quarantined status. `build_intake_reconciliation_report` reports intake status counts, aging received events, and source-key conflicts. It is an operational transport report, not a ledger or financial reconciliation.

The service does not create Finance entities and does not acknowledge Hospital outbox delivery. Cross-database delivery acknowledgement remains disabled until the caller can confirm durable acceptance and recovery behavior.

## Required before durable Finance writes

1. **Source correlation.** Hospital integer IDs must not be cast or guessed into Finance UUIDs. The consumer must compare fingerprints for duplicate event keys; identical retries are idempotent, while conflicting reuse is recorded and quarantined.
2. **Party and account mapping.** Resolve active persisted Finance Persons and the correct active Financial Account from approved configuration. Never infer parties from patient records or invent accounts.
3. **Obligation and settlement lifecycle.** An invoice event alone is not a Payment or Settlement. A received Hospital payment must not be treated as a completed Finance Payment or settled obligation without the required Finance lifecycle and evidence. The current orchestrator rejects partial settlement.
4. **Authorization and approval.** Use existing Finance application APIs and their active operation-specific AuthorizationGrant, matching approved AgentAction, recorded human Approval, and connectivity checks. The outbox is not authorization.
5. **Atomic durable outcomes.** Persist source correlation, consumer status, request fingerprint, and the outcome atomically with each Finance-side operation where the persistence model permits. Do not mark an event delivered merely because it passed validation.
6. **Cross-database recovery.** Hospital and Finance are separate databases. Keep events retryable until Finance-side durable acceptance is confirmed; reconcile stale leases, duplicates, mapping failures, and unmatched amounts.
7. **Accounting integrity.** Do not post a ledger entry directly from an invoice/payment event, use opening-balance flows to import live activity, or claim complete double-entry reporting until posting rules, chart of accounts, and required reports are implemented and tested.

## Delivery state

Validation, the Finance intake schema, the idempotent intake service, and a read-only operational report are implemented with tests. No worker is enabled, no Finance records are created by the adapter or intake service, and no event should be marked delivered solely after validation or intake. Next: build governed identity/account mapping and a separately authorized Finance lifecycle operation with durable outcomes and end-to-end recovery tests before enabling any outbox acknowledgement.
