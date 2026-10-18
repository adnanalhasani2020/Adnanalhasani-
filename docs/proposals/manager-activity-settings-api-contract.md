# Manager Activity Settings — Backend/API Contract Proposal

> **Status: proposal only.** This document supports issue #320. It is not an approved production contract, does not authorize a new policy, and must not be interpreted as permission to activate clinical or unresolved financial behavior.

## Goal and non-goals

Move the browser-local manager settings prototype toward a secure, auditable workflow while preserving the distinction between recommendations, proposed changes, and approved active configuration.

This proposal does **not** implement endpoints, grant permissions, choose unresolved business rules, or make the current `localStorage` prototype production-ready.

## Core invariants

1. **Server is authoritative.** Browser state is a presentation cache only. Never trust client-supplied actor identity, role, activity scope, approval state, or active policy.
2. **Read is not approval.** Recommendations and previews never mutate active configuration.
3. **Proposal is not activation.** Submitting a change creates a pending proposal; only a separately authorized approval action can activate an eligible proposal.
4. **Fail closed.** Missing permissions, unknown fields, stale versions, invalid transitions, persistence errors, or audit-write failures reject the operation without partial activation.
5. **Clinical and financial boundaries remain enforced.** A proposal touching a blocked or unresolved policy cannot be approved until the designated authority has recorded the required decision through the governed process.
6. **Audit is durable.** Approval/rejection and activation must be recorded atomically with their state transition. Audit history is append-only to application actors.
7. **No patient-sensitive browser persistence.** Patient data and decision evidence containing sensitive information must not be placed in `localStorage`.

## Proposed resources and operations

Names are illustrative and require architecture/security review before implementation.

| Operation | Purpose | Required behavior |
| --- | --- | --- |
| `GET /api/manager-settings/context` | Read the authenticated manager's allowed activity options, current active configuration version, and recommendations | Return only data within the caller's server-derived scope; do not return secrets or patient data |
| `GET /api/manager-settings/proposals/{id}` | Read a proposal and its status | Enforce object-level authorization on every request |
| `POST /api/manager-settings/proposals` | Submit a proposed change | Validate an allowlist of fields and values; record actor from the authenticated session; create `pending_review`; do not activate |
| `POST /api/manager-settings/proposals/{id}/decision` | Approve or reject a proposal | Require a distinct permission and valid state transition; require a reason for rejection and for policy exceptions where allowed; prevent self-approval unless an explicitly approved governance rule says otherwise |
| `GET /api/manager-settings/audit` | Review decision history | Restrict to authorized auditors/managers and allowed organization scope; support pagination and stable ordering |

The eventual route names may differ to fit the existing application architecture. Do not create these routes until their placement, identity model, permission vocabulary, and transaction boundaries have been reviewed.

## Suggested proposal shape

```json
{
  "activity": "health",
  "baseVersion": 12,
  "changes": {
    "displayPreferences": {
      "recommendationMode": "review_before_apply"
    }
  },
  "reason": "Align the manager workspace with the approved operating context"
}
```

- `activity` must be checked against server-authorized organization scope, not merely accepted from the client.
- `baseVersion` is required for optimistic concurrency. Reject stale proposals with a conflict response; never silently overwrite newer settings.
- `changes` must be validated against a server-owned schema and allowlist. Unknown keys are rejected.
- The reason is explanatory text, not proof of authorization or a substitute for a required formal decision.
- Do not accept `actorId`, `approvedBy`, `active`, or audit timestamps as trusted client fields.

## Required human decision: partial and repeated settlement policy

Issue #93 remains unresolved. The current recognition guard rejects partial settlement because the system does not yet persist and enforce remaining obligation amounts. The manager UI must present the unresolved choice explicitly; it must not silently select a cardinality model or activate financial recognition.

The decision screen should explain these alternatives in plain language:

| Option | Meaning | Required implementation before activation |
| --- | --- | --- |
| **A — Multiple settlement records** | Each settlement represents one separately identified allocation against an obligation. | Add a reviewed migration that permits multiple settlement rows per obligation; persist each exact allocation amount and currency; derive the outstanding amount from immutable allocations; enforce total allocations do not exceed the obligation; make retries idempotent and preserve audit/reversal history. |
| **B — One cumulative settlement record** | One settlement record represents the cumulative settled amount while individual allocation attempts remain separately auditable. | Preserve each allocation/attempt in durable history; define atomic cumulative updates and concurrency control; derive the remaining amount from the immutable allocation history, not from a client-supplied total; prevent duplicate allocations and lost updates. |
| **Defer partial settlement** | Keep current fail-closed behavior until an authorized accounting owner resolves the model. | Continue rejecting partial recognition. Do not fabricate a full settlement or mark an obligation satisfied after a partial payment. |

The UI must show **Defer partial settlement** as the safe default until an authorized accounting owner records Option A or B through the governed process. A manager's activity preference alone is not sufficient to override accounting invariants.

For either A or B, the proposal and decision record must include the selected option, authorized decision-maker, reason/evidence reference, effective version, timestamp, and audit event. The decision must be rejected if required authorization or durable audit persistence is unavailable. Existing settlement history must not be rewritten to fit the new policy.

Before activation, implementation must also define due-date and overdue behavior, voids/refunds/reversals, overpayments, unapplied receipts, currency matching, concurrency, and migration behavior for populated databases. Payment, Settlement, financial recognition, and LedgerEntry remain distinct lifecycle stages.

## State machine

`draft → pending_review → approved → active`

Alternative terminal/review states: `rejected`, `withdrawn`, and `superseded`.

- Approval and activation may be separate transitions if the policy requires a deployment or additional authority.
- A proposal must not skip review or move backward by directly editing its status.
- Repeated requests must be idempotent where appropriate; duplicate submissions must not produce duplicate approvals or audit events.
- Concurrent decisions must use a version check or equivalent compare-and-swap guard so only one valid transition wins.

## Authorization requirements

Authorization must be enforced in the service/API layer for every read and write, including direct object IDs. At minimum, review and define distinct permissions for:

- viewing recommendations and active settings;
- proposing changes;
- approving or rejecting proposals;
- viewing audit history;
- activating a proposal where activation is separate from approval.

The server must derive actor identity and organization scope from the authenticated principal. The UI may hide unavailable actions, but UI controls are not an authorization boundary.

## Audit event minimum

Each accepted proposal, rejection, approval, activation, and failed state transition that is required for security auditing should record the applicable fields:

- immutable event ID and proposal ID;
- authenticated actor ID and effective permission/scope;
- event type, server timestamp, and request/correlation ID;
- before/after configuration versions and a redacted before/after diff;
- decision and reason, where applicable;
- outcome and failure category, without credentials, tokens, or unnecessary sensitive data.

The state transition and its required audit event must commit atomically. If the audit write fails, the protected transition must fail too. Audit records must not be editable through normal manager APIs.

## Error and concurrency behavior

- `401`: no valid authenticated principal.
- `403`: caller lacks the required permission or scope.
- `404`: resource absent or intentionally undisclosed under the access-control policy.
- `409`: stale base version or conflicting state transition.
- `422`: schema validation or policy-governance rule rejected the proposal.
- `5xx`: persistence/audit dependency failed; no partial activation may remain.

Use the repository's established error envelope if one exists; the status meanings above are conceptual rather than a mandate to introduce a new global format.

## Persistence and transaction boundaries

Before implementation, identify the existing database, migration owner, transaction abstraction, identity/authorization middleware, and audit conventions. A single approval/activation transaction must persist the state transition and required audit record together. Use uniqueness or idempotency controls for request retries, and optimistic concurrency for versioned settings.

Do not store authoritative active settings only in browser storage. A browser reset, quota failure, or cleared cache must not change server-side policy.

## Privacy, accessibility, and client behavior

- Keep the current prototype clearly labeled as a prototype until server persistence and authorization are implemented.
- Do not place patient data, authentication credentials, or decision evidence containing sensitive information in `localStorage`.
- Treat all server-returned text as untrusted when rendering; use safe text APIs rather than HTML interpolation.
- Preserve keyboard access, visible focus, RTL support, status/error announcements, and reduced-motion behavior.
- Explain whether a setting is a recommendation, pending proposal, approved configuration, or active configuration.

## Required verification before production

1. Unauthenticated and unauthorized read/write tests, including cross-organization object-ID attempts.
2. Tests proving that proposing or previewing never changes active configuration.
3. Approval/rejection tests proving actor, reason, timestamps, versions, and redacted before/after diff are complete.
4. Fault-injection tests for database failure and audit-write failure, asserting no partial state change.
5. Concurrent approval/update tests proving stale versions are rejected and only one transition wins.
6. Retry/idempotency tests for duplicate proposal and decision requests.
7. Privacy checks confirming no patient-sensitive data is persisted in browser storage.
8. Migration-upgrade and rollback rehearsal against a populated database, plus deployment documentation.
9. For partial settlements, prove remaining-balance derivation, concurrent allocation safety, duplicate retries, over-allocation rejection, currency isolation, and preservation of historical records.

## Human decisions still required

This proposal deliberately does not decide:
- the authoritative manager/organization role and scope model;
- whether proposer and approver must always be different people;
- which activity-specific settings are configurable and who owns each policy;
- the formal authorities and evidence required for clinical or financial decisions;
- whether approval immediately activates a setting or requires a separate release step;
- retention periods and access policy for audit records;
- **Issue #93:** whether partial settlements use multiple settlement records (Option A), one cumulative settlement record with separate allocation history (Option B), or remain deferred.

Record these decisions through the repository's established governance process before implementing policy-sensitive behavior.

## Rollout and rollback

1. Review this proposal against the existing application architecture and security conventions.
2. Approve the identity, authorization, audit, and policy ownership contracts.
3. Implement behind a disabled feature flag with migrations reviewed by the database owner.
4. Run the verification suite and populated-database migration rehearsal.
5. Enable for a limited authorized cohort only after explicit approval.
6. Roll back by disabling the feature and restoring the prior compatible application version; do not delete audit history or silently reverse approved business decisions.

## Traceability

- Related issue: #320 — Production readiness for manager activity settings.
- Related issue: #93 — Settlement cardinality for partial and repeated payments.
- Current prototype: `docs/prototypes/admin-decision-settings.html`.
- Health scope remains constrained by `docs/07-التنفيذ/EXEC-0006-الأساس-الصحي.md`.
