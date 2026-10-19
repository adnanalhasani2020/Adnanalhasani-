# Commerce returns and payment integrity audit — 2026-10-19

## Scope and current baseline

Reviewed the persisted Sale return lifecycle, Invoice issue/void lifecycle, PaymentApplication, schema guards, settlement-recognition application, and relevant tests. PR #465 is merged (atomic Payment completion checks in the application layer). PR #466 is merged (regression coverage for preventing a second issued Invoice per Sale on UPDATE).

## New correctness gap addressed in this change

Before this change, the PaymentApplication refused to complete a Payment when its linked Invoice was no longer `issued` or its linked Obligation was no longer eligible. That is correct for application callers, but a direct SQL write could bypass the service-level checks and set `payments.state='completed'` against a void Invoice or terminal Obligation. This change adds database triggers for INSERT and UPDATE, plus regression tests for both invalid cases and a valid completion. The triggers are defense in depth; they do not replace the persisted authorization and approval checks in PaymentApplication.

## Four highest-priority remaining gaps

1. **P0 — Commerce-to-Finance source mapping and reconciliation.** There is a Finance-owned persisted recognition application and Hospital intake correlation, but no verified automatic contract from Commerce Sale/Invoice/Payment events to the canonical Finance records. Do not infer a payment from a Sale or create a second ledger writer.
2. **P0 — Partial settlement recognition and durable remaining balance.** Payment creation already reserves aggregate capacity for initiated, pending, and completed payments linked directly or through Settlement, so the system has a useful anti-overpayment guard. However, persisted recognition still requires a Payment amount equal to the full Obligation amount, and Settlement schema cardinality/recognition semantics do not yet establish a complete multiple-partial-settlement ledger workflow. Preserve the current fail-closed recognition rule until balance, concurrency, and replay semantics are migrated and tested end to end.
3. **P1 — Authorized customer refund and financial reversal.** Sale return restores stock through an append-only movement, but that is not a provider refund or ledger reversal. A complete workflow needs explicit return/refund authorization, partial quantity/value rules, provider idempotency, credit-note policy, append-only Finance reversal/correction, and reconciliation. Do not create a parallel stock-return implementation.
4. **P1 — Jurisdiction-specific invoice/tax policy.** Current Invoice records do not prove tax compliance. Confirm jurisdiction and approved issuer, tax, rounding, mandatory-field, numbering, and credit-note rules before enabling compliance claims or automatic tax calculation.

## Existing behavior to reuse — no duplication

- SaleApplication's governed return path and compensating inventory movement.
- InvoiceApplication and its issued-per-Sale uniqueness invariant.
- PaymentApplication's persisted authorization, approval, optimistic version checks, currency/party/link validation, and aggregate obligation-capacity reservation.
- PersistedSettlementRecognitionApplication as the Finance-owned transaction/ledger persistence boundary.
- External event intake's stable event identity, conflict quarantine, and explicit Payment mapping.

## Release gates

- The new database guard and tests must pass Python tests and Stage 7 Automated Tests on the exact PR head.
- Do not enable automatic refunds, partial settlement recognition, or jurisdiction-specific tax behavior without approved policy and end-to-end tests.
- This is source-level engineering review, not a production certification.
