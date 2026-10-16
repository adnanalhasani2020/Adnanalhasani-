# EXEC-0042 — Voucher identity and cancellation contract gap

**Status:** Gap analysis only. This document is not an approved business or accounting decision.

## Why this gap matters

The repository's open-question register keeps **Q-0031 — “What is the nature of the voucher/instrument?”** in **Decision Required**. The generic `Instrument` persistence primitive supports issuance, activation, redemption, expiry and revocation. Those generic lifecycle actions do not, by themselves, define an accounting receipt voucher or payment voucher.

In particular, `revoke` must not be treated as synonymous with “cancel voucher” without a domain decision. A voucher may be only a reference/document, may record a receipt/payment, or may be tied to a separate Payment/Settlement/Finance workflow. Each interpretation has different consequences.

## Existing boundaries that must remain intact

- `Instrument` is not automatically a `Payment`.
- `Payment` is not `Settlement`.
- `Settlement` is not a `FinancialTransaction` or `LedgerEntry`.
- Finance owns recognized financial transactions, ledger entries and derived balances.
- A reversal/correction must preserve history rather than delete the prior record.
- Authorization must be explicit; possession of a voucher ID or a generic actor role does not grant cancellation authority.
- Repeating a cancellation request must not silently create repeated financial effects.

## Decisions required before implementing receipt/payment voucher cancellation

1. **Voucher meaning:** Is a voucher a document/reference only, a receipt/payment record, or a representation linked to an existing Payment? Are receipt and payment vouchers distinct types?
2. **Lifecycle:** Which states and transitions are valid? Is cancellation permitted before confirmation only, or after confirmation too? Does “cancel” mean voiding the document, revoking an unused instrument, or recording a financial reversal?
3. **Financial consequences:** Which existing owner (Payments, Settlement, Finance, or another approved domain) records any reversal? Do not directly mutate a balance or create a ledger entry from the generic Instrument lifecycle.
4. **Partial and repeated operations:** How are partial receipts/payments represented? How is a repeated cancellation detected? What happens if several vouchers refer to one obligation?
5. **Authorization:** Which already-approved action code, target scope, context, approver and reason are required? Do not invent an authorization code or bypass the persisted approval pattern.
6. **History and correction:** Which immutable history records are required, and how are mistaken cancellations corrected without erasing the original event?
7. **Concurrency:** What expected-version/idempotency contract protects against simultaneous or repeated cancellation attempts?

## Safe implementation boundary until the decision is recorded

- Keep the generic `Instrument` lifecycle as-is; do not add a `cancel` transition or treat `revoke` as accounting cancellation.
- Do not add receipt/payment voucher tables, subtype codes, schema migrations, new authorization action codes, or financial posting effects on the basis of this gap analysis.
- Once the decision is approved in the official decision register and mapped to requirements/specifications, implement only the approved lifecycle with atomic state/history changes, expected-version checks, authorization, and tests for replay, partials, failure and no-duplicate financial effects.

## Traceability

- Open question: `Q-0031` in `docs/01-المتطلبات/07-سجل-الأسئلة-المفتوحة.md`.
- Payment/Settlement/Finance boundary: `docs/06-المواصفات/SPEC-0008-الدفع-والتسوية.md`.
- Generic Instrument chronology work: PR #212.
- Payment timestamp validation work: PR #214.

This record clarifies the implementation blocker; it does **not** close Q-0031 or authorize a new accounting lifecycle.
