# Hospital–Finance Transport Operations Runbook

## Purpose and safety boundary

Use this guide with the read-only report returned by
`build_hospital_finance_transport_report(hospital_db, finance_db)`.

The report compares Hospital outbox event keys with Finance intake identities. It
is **not** a financial reconciliation and does not prove that a payment,
settlement, financial transaction, or ledger entry exists. Neither the report nor
this runbook performs delivery retries, acknowledges outbox events, changes
mappings, or creates financial records.

## Disposition response guide

| Report disposition | Operator response | Do not do |
| --- | --- | --- |
| `awaiting_intake` | Check the approved consumer and transport path. Use only the supported retry/recovery procedure. | Do not manually mark an event delivered. |
| `delivered_without_intake` | Compare the delivery acknowledgement with durable Finance intake and recovery logs. Establish whether the intake commit succeeded before considering replay. | Do not assume that a delivery acknowledgement proves durable acceptance. |
| `event_type_mismatch` | Stop automated recovery and reconcile the outbox and intake event types against original source evidence. | Do not replay, remap, or post financially until the mismatch is resolved through an approved process. |
| `received_not_mapped` | Resolve the source identity against an approved, active Finance mapping and the applicable organization/activity scope. | Do not create a payment, settlement, or ledger entry solely because intake succeeded. |
| `mapped_source_only` | Independently inspect the Finance payment, settlement, transaction, and ledger lifecycle records that apply to the event. | Do not treat a mapping as proof of financial recognition. |
| `quarantined` | Reconcile the source-key conflict against the original event identity and retained audit evidence. Preserve the existing Finance mapping until an authorized resolution is recorded. | Do not overwrite the original event or silently clear quarantine. |
| `rejected` | Check the source event against the versioned allowlist and schema; correct the source through an approved, auditable process. | Do not bypass validation or mutate a previously accepted event under the same key. |
| Intake without outbox | Confirm that both connections point to the intended databases; review retention, migration, and source history. | Do not delete the intake row or process it financially without establishing provenance. |

The report includes conservative `operator_guidance` text alongside each row. It
is informational only and must not be interpreted as an executable command.

## Safe recovery sequence

1. **Capture the report.** Record the report timestamp, counts, affected event
   keys, and the operator investigating. Do not copy payloads, patient details,
   payment references, or error text into a general incident log.
2. **Classify the transport state.** Use the report disposition, not assumptions
   about what the event means financially.
3. **Verify persistence before replay.** For a claimed delivery without intake,
   determine whether Finance committed the source identity. A replay is safe only
   through an approved consumer that enforces source-key idempotency and detects
   fingerprint conflicts.
4. **Preserve conflicts.** A repeated source key with a different fingerprint
   requires manual reconciliation. Do not replace the original fingerprint or
   mapped target as a shortcut.
5. **Verify financial finality separately.** Transport intake and mapping do not
   establish Payment completion, Settlement, ledger posting, or balance changes.
   Use the Finance domain's approved workflow and recorded authorization,
   AgentAction, and human approval for any financial operation.
6. **Close with evidence.** Record the resolution and its authorization/audit
   reference. Re-run the read-only report after the supported recovery path to
   confirm transport status; separately confirm any required financial outcome.

## Escalation and unresolved policy decisions

Escalate to the responsible administrator/accounting owner when any action would
choose or change: invoice recognition timing, account/party mapping, partial
payment treatment, correction/reversal policy, or delivery failure policy. The
administrator-facing prototype currently stores preferences locally only; it is
not an operational policy store. No choice in that prototype authorizes ledger
writes or activates the Hospital–Finance consumer.

## Known integration limits

- Hospital integer IDs and Finance UUIDs are separate identity spaces; never cast
  or guess IDs across systems.
- Hospital and Finance use separate SQLite databases, so a single atomic commit
  across both stores is not available. Use a durable consumer, idempotent replay,
  and explicit reconciliation.
- Hospital accepts partial payments, while the current Finance recognition
  orchestrator rejects partial settlement until remaining obligation amounts are
  tracked. Do not work around this restriction by fabricating a full settlement.
- No end-to-end consumer is enabled by this report. The report is read-only.
