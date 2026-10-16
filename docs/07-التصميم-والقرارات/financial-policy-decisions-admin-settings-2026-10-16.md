# Financial policy decisions — owner-approved baseline (2026-10-16)

## Status and governance

This record captures the project owner's decisions communicated on 2026-10-16. It is a decision proposal/impact record for review, not proof that runtime behavior, UI, schema, or tests are already implemented. The official decision register and impacted SPEC/REQ traceability must be updated through the repository's governance process before any requirement is represented as implemented or accepted.

## 1. Supported currencies and organization default

**Owner decision**
- Supported currencies: Saudi riyal (SAR), Yemeni rial (YER), and US dollar (USD).
- Organization default/accounting currency: YER.
- Monetary values: two decimal places in the application's financial policy.

**Required implementation contract**
- These options must be editable from a dedicated Finance/Accounting Settings screen available only to an authorized administrator.
- Persist settings per organization/tenant; do not use an unscoped global setting in a multi-organization deployment.
- Store currency codes as ISO 4217 codes, validate against the configured supported-currency list, and reject unsupported codes at the application boundary and database boundary where feasible.
- Use exact decimal/integer-minor-unit arithmetic; never binary floating point for money.
- Apply the configured two-decimal display/entry policy consistently. Document the currency-specific rounding rule and apply it once at defined boundaries; do not silently round intermediate calculations.
- Changing the default currency must not rewrite historical documents, postings, settlement amounts, or ledger history. Existing records retain their original currency and captured accounting values.

## 2. Exchange-rate policy

**Recommended safe default pending review**
- Exchange rates are explicitly configured by an authorized administrator; do not silently depend on an undocumented public API or fabricate a rate.
- Settings support source/reference, effective-from timestamp, rate direction/quotation convention, and audit metadata (who changed it, when, and why).
- A rate must be positive, finite, and unambiguous about base/quote currencies. Reject missing, zero, negative, malformed, or stale rates when a transaction requires conversion.
- Capture the exact rate, rate timestamp, source/reference, source amount/currency, and converted amount/currency with the financial transaction/posting so later rate changes do not rewrite history.
- Define a deterministic rounding rule for converted values and record any exchange difference through an approved accounting treatment; do not invent an account mapping or automatic FX journal until the chart-of-accounts/ledger contract authorizes it.
- Rate configuration and changes must be auditable and administrator-authorized. The UI should clearly distinguish the current configured rate from the rate captured by historical transactions.

## 3. Voucher cancellation

**Owner approval:** adopt the proposed distinction between unposted cancellation and reversal of posted accounting effects.

- An unposted voucher may transition to cancelled only through an authorized, reason-recorded, auditable operation; preserve the original voucher and history.
- A posted voucher must not be deleted or have its posted ledger effect silently removed/edited. Correct it through a separately identifiable reversing entry linked to the original posting, subject to the approved posting and authorization contract.
- Preserve original and reversal records, actor/context, reason, timestamps, linkage, and audit trail.
- Prevent duplicate cancellation/reversal under retries or concurrent requests.
- Do not claim the runtime implementation is complete until the lifecycle, schema/ledger linkage, authorization, migration, and tests are implemented and CI passes.
- PR #215 is documented in issue #220 as documentation-only and does not itself implement voucher cancellation. Q-0031 remains open until the approved contract is mapped to the official decision/specification process and implementation is verified.

## 4. Settlement cardinality

**Owner decision:** the cardinality is a configurable program setting chosen by an authorized administrator, rather than a hard-coded assumption.

- Provide an organization-scoped Finance Settings option for whether an obligation may have one settlement record or multiple settlement records.
- Default conservatively to the currently supported schema behavior (one settlement per obligation) until a migration and data model for multiple settlements is reviewed and released. Do not claim that multiple settlements are supported merely because a UI toggle exists.
- If multiple settlements are enabled, each settlement must be separately identifiable and linked to its obligation/payment evidence, with partial/repeated amounts represented explicitly. Preserve the history of each attempt and prevent the same payment/settlement from being counted twice.
- Define how totals, outstanding amount, partial completion, retries, and reconciliation are computed. Payment, Settlement, financial recognition, and Ledger remain distinct concepts.
- Changing this setting must not reinterpret or rewrite existing settlement history. Disallow changes that conflict with existing records unless a reviewed migration/reconciliation path is provided.
- Until implementation and schema are verified, issue #93 remains an implementation blocker for multi-settlement support.

## 5. End-to-end financial acceptance profile

**Owner decision:** the program must expose an administrator-configurable acceptance/verification profile for the financial workflow.

- Settings may enable/disable named validation profiles or acceptance suites; they must not allow an administrator to weaken core invariants or bypass authorization, balancing, audit, idempotency, or integrity checks.
- The mandatory baseline is: opening balances -> recognized financial transaction -> posted ledger entry -> persisted balance read -> ledger/report reconciliation -> settlement currency consistency -> duplicate/retry test -> database close/reopen and read-back.
- Negative tests must prove rejected operations leave no partial document state, history, ledger, balance, or settlement effects.
- Verify debit/credit balance, account ownership/mapping, currency consistency, cutoff/time handling, and that historical records are not silently rewritten.
- The profile must report which exact test suite/version ran, its result, time, and software revision. A configurable profile is not a substitute for CI or a successful test run on the exact candidate commit.
- The settings screen must not present a failed or unrun suite as passed, and ordinary business users must not be able to override test outcomes.

## 6. Delivery and merge gates

1. Reconcile PR #212 with current main; run Python tests and Stage 7 Automated Tests on the exact final head.
2. Reconcile PR #216 with current main; inspect migrations 0022–0026 in lexical order and dependent application changes; run required workflows and migration-upgrade tests against a populated database.
3. Review the chart-of-accounts/account-to-ledger mapping contract before persistence or posting integration.
4. Test settings authorization, organization isolation, invalid values, audit history, concurrency, and historical immutability.
5. Do not merge a financial change unless its exact final head has complete diff review, required CI passes, and any schema migration passes populated-database upgrade testing.
6. Do not start Stage 12B, add a second ledger, or introduce automatic money movement under this decision record.

## 7. Outstanding matters not decided here

This record does not decide inventory quantity correction (issue #102), inventory gating of Sale creation (issue #162), or the idempotent-execution receipt/schema contract (issue #137). Those remain separate decisions and must not be inferred from the financial settings decisions above.
