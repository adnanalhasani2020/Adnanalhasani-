# Hospital-Finance integration settings workflow

This phase adds a versioned Finance-owned persistence boundary for manager/accounting-owner mapping decisions. It does not enable the outbox worker, create Finance Payments or Settlements, or authorize ledger writes.

## Safe configuration behavior

- Each proposal is scoped to an existing Finance Activity and an explicit Hospital scope reference.
- The selected Finance Person and Financial Account must already exist and be active; the account must belong to that Person.
- Proposals require a separate active Finance Person to approve them. A proposer cannot approve their own proposal.
- Approved versions supersede the prior active version for the same Activity/scope. Historical versions remain persisted.
- Database guards reject suspending/ending an Activity, deactivating the mapped Finance Person, or closing the mapped Financial Account while related settings are pending approval or active. Pause/reject the setting first; this prevents a stale active mapping from silently surviving a reference-state change.
- Each proposal is an immutable versioned snapshot: correcting scope, account, person, or policy requires a new proposal rather than editing history. Approval metadata can only be written during the pending-to-active transition, and database guards reject invalid status transitions or approval by the proposer.
- Proposal, approval, and supersession events are written to the append-only `audit_records` table inside the same database savepoint as the settings transition. If the required audit write fails, the settings change rolls back.
- Partial payments remain rejected until remaining-obligation support exists. Currency conversion and inferred rounding are not enabled; amount/currency must match exactly.
- Invoice policy choices are stored explicitly, but this settings record alone does not create an obligation. Invoice events remain unacknowledged until a separately approved Finance obligation lifecycle exists.
- These settings are not a substitute for the operation-specific AuthorizationGrant, approved AgentAction, human Approval, and Finance recognition application required for every financial write.

## Current implementation boundary

The HospitalFinanceIntegrationSettingsApplication supplies persisted proposal, approval, active lookup, and versioning. The opt-in consumer is still not automatically scheduled or wired to this application. A manager UI and authenticated endpoint must call this Finance-owned application through the existing authenticated Finance surface before settings can be considered user-configurable end to end. Do not expose database UUID entry as a free-form browser setting; present validated existing Persons/Accounts.
