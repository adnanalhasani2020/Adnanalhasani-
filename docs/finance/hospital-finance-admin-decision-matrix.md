# Hospital–Finance Administrator Decision Matrix

## Purpose

This matrix captures the business and accounting choices that must be explicitly approved before the Hospital outbox can create or settle Finance-owned records. It is an implementation contract for the administrator settings workflow, not authorization to post transactions. The existing Hospital manager settings page is a preview/proposal workflow for operational modules; it must not be represented as a live accounting configuration screen until a separately authorized, persisted settings API exists.

## Non-negotiable boundaries

- Hospital remains the source of truth for operational invoices and received-payment facts.
- Finance remains the sole owner of Finance Payments, Settlements, FinancialTransactions, LedgerEntries, and balances.
- Never infer a Finance Person or Financial Account from a Hospital integer or patient identifier.
- Never create a ledger entry directly from an outbox event or use opening-balance recognition to import live activity.
- Never mark an outbox event delivered just because it passed validation or was accepted into intake.
- Store only the minimum financial metadata required for correlation; do not send patient identifiers, diagnoses, clinical notes, or raw payment references to Finance intake.
- If a required decision is unset, inactive, inconsistent, or expired, keep the event pending/rejected/quarantined and show an actionable configuration error. Do not silently select a default.

## Decisions that require an authorized administrator/accounting owner

The future settings UI should present these choices explicitly, explain their consequences, validate them against persisted Finance records, and record the approver, timestamp, configuration version, and audit reference. An option selected in a draft is not active until server-side authorization and approval complete.

| Decision | Choices / required input | Safe behavior until approved |
| --- | --- | --- |
| Organization/activity scope | Select the approved Hospital organization/activity scope that is allowed to use the mapping. | Do not process events outside a configured scope. |
| Hospital payer/party mapping | Select an existing active Finance Person using a searchable, non-clinical business identity. No free-form UUID guessing. | Keep the event unmapped. Never use patient ID as a Finance Person ID. |
| Financial Account mapping | Select an existing active Finance Financial Account belonging to the selected Person and permitted scope. | Keep the event unmapped; never create or infer an account automatically. |
| Invoice recognition | Choose whether an invoice is an operational receivable only, or whether a separately approved Finance obligation workflow is required. Define the accounting date and required evidence. | Invoice events do not create FinancialTransactions or LedgerEntries. |
| Payment recognition trigger | Choose the approved evidence required before a Hospital received-payment event may initiate Finance Payment/Settlement lifecycle. | Intake is not proof of a completed Finance Payment or settled obligation. |
| Partial payments | Choose whether partial settlement is supported; if yes, specify how remaining obligation balance and cumulative settlements are tracked and validated. | Reject/quarantine partial settlements until the Finance lifecycle supports remaining balances. |
| Account/category and posting rules | Select existing approved chart-of-accounts mappings and documented debit/credit/sign conventions through the Finance-owned accounting configuration. | Do not synthesize a ledger entry or infer a chart account. |
| Tax, discount, refund, void, and reversal | Define treatment and authorization requirements for each supported case, including append-only corrections and reversal entries. | Stop automatic recognition for unsupported cases; preserve the source event and audit evidence. |
| Currency and rounding | Select permitted currencies, minor-unit interpretation, and an approved rounding policy. Record captured currency/rate facts when applicable. | Reject unsupported or ambiguous currency/amount representations. |
| Duplicate/replay policy | Reuse the stable source event key and canonical payload fingerprint; distinguish exact retries from same-key/different-payload conflicts. | Exact retries are idempotent; conflicts are quarantined and require reconciliation. |
| Approval and operational ownership | Identify the authorized accounting owner/reviewer and who may prepare versus approve mapping changes. | No active configuration or financial write without required approval and valid Finance authorization. |

## Settings workflow requirements

1. **Draft:** allow an administrator to prepare choices without changing production behavior.
2. **Validation:** resolve all selected identifiers against persisted Finance records; verify active state, relationship consistency, scope, currency, and permissions server-side.
3. **Independent review:** require the configured reviewer to approve or reject the exact configuration version. The proposer must not approve their own proposal.
4. **Activation:** activate atomically only after approval; record immutable version history and audit provenance. A failed activation must leave the previous active version unchanged.
5. **Change control:** mapping or policy changes apply prospectively. Do not rewrite the meaning of previously received events; retain the configuration version used for each decision.
6. **Readiness view:** display missing decisions, invalid/inactive mappings, pending approvals, quarantined conflicts, and transport lag. Clearly distinguish transport state from financial recognition and ledger posting.
7. **Disable/pause:** provide an authorized way to pause new automatic processing without deleting queued events or changing historical ledger facts.
8. **Least privilege:** never expose bearer tokens in logs or browser storage; require authenticated API access and server-side role checks.

## Activation gates for the integration

A delivery worker and Hospital outbox acknowledgement must remain disabled until all of the following exist:

- approved settings persisted with versioning and audit history;
- a Finance-owned application operation that reloads persisted Payment, Settlement, Obligation, Financial Account, authorization grant, AgentAction, and Approval records;
- atomic Finance persistence of source correlation, operation outcome, transaction/ledger records, and applicable lifecycle history;
- canonical ledger constraints and approved posting rules;
- idempotent replay handling and explicit same-key/different-payload conflict quarantine;
- partial-payment support or an explicit rule that safely rejects it;
- recovery/reconciliation for interrupted delivery across the separate Hospital and Finance databases.

Hospital and Finance are separate databases, so do not claim a distributed atomic transaction. Use retryable at-least-once delivery, idempotent Finance handling, durable outcomes, and reconciliation.

## Implementation status

This document records required choices and safety gates only. It does not implement the settings API, approve any accounting policy, create Finance records, enable a worker, or acknowledge Hospital outbox events. The current operational settings screen is not a substitute for the accounting decision workflow described here.
