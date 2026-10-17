# Manager Activity Settings — API Contract Proposal

**Status:** Proposal for implementation review; not an approved security policy or domain decision.  
**Tracking issue:** #320  
**Scope:** Define a safe boundary between a manager's proposed settings and the configuration currently active in the system.

## Safety invariants

1. Activity-specific guidance and previews are recommendations only. Saving a proposal must not activate it.
2. The server is authoritative for identity, authorization, validation, versioning, and audit. Client-side controls are not security controls.
3. Clinical capabilities and unresolved financial policies remain blocked unless the authorized owner has recorded the required decision through the project's official decision process.
4. Never accept patient records, clinical notes, passwords, or other sensitive free text as settings payload. Do not persist sensitive settings in browser `localStorage`.
5. Fail closed on missing identity, insufficient authority, stale version, invalid state transition, or audit-write failure.
6. A successful response must not claim that a proposed setting is active unless an explicit authorized activation transaction has completed.

## Resource model

Keep these conceptual resources distinct:

- **Active configuration:** the validated configuration currently used by the server, with a monotonically increasing version.
- **Proposal:** a requested change, its submitter, status, rationale code (not unrestricted sensitive notes), and the version it was based on.
- **Decision:** an approval or rejection by an authorized actor, with timestamp and reason code.
- **Audit event:** append-only evidence of proposal, decision, activation, and failure where policy requires it. Audit events are not themselves authorization grants.

Suggested proposal states: `pending`, `approved`, `rejected`, `withdrawn`. State names and transitions require review against the repository's existing conventions before implementation. A proposal being approved does not automatically mean it has been activated.

## Proposed API shape

These routes are illustrative and must be aligned with the existing application/router conventions before implementation.

### Read

`GET /api/admin/settings`

- Requires authenticated manager identity and server-side read permission.
- Returns only the active configuration, its version, and safe metadata.
- Must not expose audit-only identity data or another manager's private proposal content.

### Create proposal

`POST /api/admin/settings/proposals`

Example request:

```json
{
  "base_version": 12,
  "activity": "health",
  "preferences": {
    "approval_mode": "all_material_changes",
    "audit_mode": "all_changes"
  },
  "requested_policy_reviews": ["clinical_scope"],
  "reason_code": "OPERATING_MODEL_REVIEW"
}
```

- Authenticate and authorize on the server.
- Validate every field against an allowlist and the current approved decision/specification state.
- Treat `activity`, preferences, and requested reviews as proposed configuration only.
- Return a proposal identifier and `pending` status; do not mutate active configuration.
- Reject unknown fields and free-text fields that could collect patient or secret data.

### Review proposal

`POST /api/admin/settings/proposals/{proposal_id}/decision`

Example request:

```json
{
  "decision": "reject",
  "reason_code": "POLICY_NOT_APPROVED"
}
```

- Only an actor with explicit server-validated review authority may decide.
- Record the authenticated actor identity and server timestamp; never trust actor IDs or timestamps supplied by the client.
- Prevent self-approval unless the repository's authorized policy explicitly permits it.
- Use an atomic conditional update so a proposal cannot be decided twice or overwrite a concurrent decision.
- A rejection must leave active configuration unchanged.

### Activation

If the existing architecture requires a separate activation operation, it must be a distinct authorized command, conditional on the approved proposal and expected active version. Approval alone must not silently activate settings. Activation and its audit event must commit atomically; if audit persistence fails, the configuration change must roll back.

## Response and concurrency rules

- Include stable resource IDs, state, active version, and server-generated timestamps where relevant.
- Use the repository's established error envelope; do not introduce a competing format without review.
- Return a conflict response for stale `base_version` or concurrent state changes and require the client to reload and resubmit.
- Do not automatically retry a non-idempotent decision/activation command without an approved idempotency contract.
- Never return secrets or patient-sensitive data in errors or logs.

## Audit minimum

For each proposal, decision, activation, and rejected privileged attempt where required by existing policy, record:

- event type and target resource ID;
- authenticated actor reference and authorization outcome;
- server timestamp;
- prior and resulting state/version;
- before/after configuration values or a privacy-safe canonical diff;
- reason code and correlation/reference ID;
- outcome, including failure category without secret payloads.

Audit storage must be append-only through the existing approved audit/provenance mechanism. Do not assume that writing an audit row grants authorization.

## Required tests before production use

- unauthenticated and unauthorized read/propose/decide/activate attempts fail closed;
- client-supplied actor identity and timestamps are ignored or rejected;
- unknown settings, unsupported values, and unapproved clinical/financial policy changes are rejected;
- proposal creation does not change active configuration;
- rejection leaves active configuration and its version unchanged;
- approval and activation are separate, and activation requires the required authority and expected version;
- concurrent decisions and stale-version activation cannot both succeed;
- audit events contain actor, timestamp, outcome, and before/after evidence;
- audit-write and persistence failures roll back state changes;
- configuration survives database reopen and migration/rollback tests;
- UI keyboard navigation, focus, labels, error announcements, and reduced-motion preferences are checked;
- no patient-sensitive data is written to browser storage, logs, telemetry, or error messages.

## Rollout and rollback

1. Inspect existing router, identity, authorization, persistence, migration, and audit abstractions before choosing implementation locations.
2. Map every field and transition to approved requirements/specifications and existing authorization codes; do not invent new authority.
3. Add migrations only after schema ownership and upgrade/rollback behavior are reviewed.
4. Deploy read-only capability first if useful, then proposal/review flows behind an explicit feature flag.
5. Keep activation disabled until authorization, audit atomicity, concurrency, and migration tests pass.
6. Roll back by disabling the feature and reverting the application/migration using the repository's supported migration strategy; do not delete audit history or rewrite historical decisions.

## Explicit non-goals

This proposal does not approve new clinical workflows, medication/laboratory functionality, financial policy, automatic policy activation, a new authorization action code, or a new audit schema. The current HTML prototype remains non-production until the acceptance criteria in #320 are implemented and verified.
