# Hospital–Finance Manager Decision Screen Contract

**Status:** implementation contract; no runtime behavior is enabled by this document.  
**Goal:** let an authorized manager choose the accounting behavior that fits the hospital, persist the choice as a proposal, and require a distinct authorized Finance approver before it can become active.

## Current repository facts

- `hospital/web/settings.html` is an operational profile preview. Its module choices are not accounting policy and do not activate Finance behavior.
- `docs/prototypes/admin-decision-settings.html` is a local-only prototype. Its choices are not persisted to the server and must not be treated as approved configuration.
- `HospitalFinanceIntegrationSettingsApplication` already persists versioned Finance-owned proposals and requires a distinct active Finance Person to approve them.
- Finance integration settings require an existing Finance Activity, an explicit Hospital scope reference, an active Finance Person, and an active Financial Account owned by that Person.
- The current recognized-payment path only supports an already-governed, fully settled Finance payment. Partial settlement remains blocked. Invoice events must not be acknowledged until a governed Finance invoice/obligation lifecycle exists.
- Hospital and Finance may use separate database connections and do not provide a distributed atomic transaction. Delivery must remain opt-in and retryable, with durable idempotency and reconciliation.

## Screen requirements

The manager screen must be reachable from the Hospital application and must clearly distinguish **proposal**, **approved**, **active**, **blocked**, and **reconciliation required** states.

### Choices presented to the manager

1. **Hospital scope:** choose an existing, server-provided scope; do not accept arbitrary patient identifiers or infer scope from patient data.
2. **Finance activity:** choose from existing active Finance Activities.
3. **Finance owner and account:** choose an active persisted Finance Person, then an active Financial Account owned by that person. Show human-readable labels; do not ask the manager to type UUIDs.
4. **Invoice handling:**
   - Keep Hospital invoices operational only.
   - Request a separately governed Finance obligation workflow.
   The second option records intent only; it must not create an obligation or acknowledge invoice events.
5. **Payment handling:**
   - Manual review only (safe default).
   - Recognize only a fully settled, already-governed Finance payment.
6. **Partial payments:** keep blocked until a persisted remaining-obligation model and partial-settlement lifecycle are implemented.
7. **Currency:** require exact amount/currency matching. Do not offer automatic conversion or rounding until policy and rate provenance are approved.
8. **Pause integration:** allow an authorized operator to pause new processing without deleting queued events or changing historical ledger facts.

### Proposal and approval flow

- The proposing user is derived from the authenticated server-side identity, never from request JSON.
- Saving creates a versioned pending proposal; it does not activate the mapping, enable a worker, or write a ledger entry.
- A distinct authorized Finance approver must approve or reject the proposal. Self-approval is prohibited.
- Rejection requires a reason. Approval/rejection and later activation are recorded in immutable audit history.
- Every decision uses optimistic version checks so stale screens cannot overwrite a newer decision.
- Changing an active choice creates a new version; historical proposals remain inspectable.
- UI success messages must say **“proposal saved”** or **“decision recorded”**, never “integration enabled” unless a separate activation gate has actually completed.

## Required API boundary before wiring the screen

Implement authenticated, role-checked Finance-owned endpoints for:

- Reading eligible Activities, active Persons, and Accounts owned by each Person.
- Reading current active mapping and version history for an authorized Hospital scope.
- Creating a pending mapping proposal.
- Approving/rejecting a proposal with expected version and reason.
- Reading integration pause state and reconciliation/transport status.

The endpoints must call `HospitalFinanceIntegrationSettingsApplication` using the canonical Finance persistence connection. Do not call it against the Hospital-only database or copy Finance tables into a second source of truth.

Before implementing the endpoints, resolve the identity bridge between a Hospital authenticated user and a persisted Finance Person. A Hospital integer user ID is not a Finance UUID. Do not let the browser submit an arbitrary proposer/approver UUID as proof of identity.

## Non-negotiable safety gates

- Keep the outbox consumer disabled by default; settings alone do not authorize financial writes.
- The consumer must reload and validate the approved active mapping at processing time, not trust a stale browser payload.
- Finance recognition still requires its operation-specific persisted Payment, Settlement, Obligation, account, AuthorizationGrant, AgentAction, and Approval contracts.
- Preserve the original Hospital invoice/payment records and their audit history.
- Use idempotent at-least-once delivery, fenced leases, durable Finance outcomes, and reconciliation across the two database transactions.
- Do not acknowledge invoice events until the invoice/obligation lifecycle is explicitly implemented and approved.
- Do not enable partial-payment recognition until remaining-balance semantics, repeated partial payments, overpayment, reversal/refund, and crash/retry behavior are supported.
- Run only targeted tests for the changed route/application contract, plus migration checks when schema changes are introduced. Do not run the full suite unless a wider change makes it necessary.

## Acceptance criteria

1. The manager can review choices in the application and sees the currently active version and pending proposals.
2. Only server-provided eligible records can be selected; stale/inactive owners or accounts are rejected server-side.
3. Proposal creation and review are authenticated, authorized, audited, versioned, and non-self-approvable.
4. Saving or approving a proposal does not itself enable delivery or write ledger records.
5. Pausing integration preserves the queue and historical facts.
6. UI state and success messages accurately reflect persisted server state.
7. The feature remains blocked from production activation until the identity bridge, Finance authorization lifecycle, invoice policy, recovery/reconciliation, and required verification are complete.
