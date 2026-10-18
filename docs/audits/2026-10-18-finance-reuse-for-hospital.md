# Finance capability inventory for hospital reuse

**Reviewed:** 2026-10-18  
**Status:** read-only architecture audit; no runtime behavior or data changed.  
**Purpose:** reuse existing accounting/financial capabilities in the hospital instead of rebuilding duplicate classes.

## Executive conclusion

The repository already has useful reusable Finance and commercial building blocks. The hospital should not create parallel generic classes for invoice, payment, obligation, financial account, ledger balance, authorization, history, or idempotency before adapting and validating the existing boundaries.

However, the current Finance model is **not a complete double-entry accounting system**, and no verified end-to-end adapter connects hospital invoices/payments to the persisted Finance ledger. Reuse must therefore be selective: reuse the existing concepts and application services where their contracts fit; add a small adapter and hospital-specific source mapping only after accounting policy and identity mapping are approved.

## Existing components and reuse guidance

| Existing component | What it owns / provides | Hospital reuse recommendation | Important boundary |
|---|---|---|---|
| `domain_commerce.Invoice` + `InvoiceApplication` | Persisted issued/void invoice lifecycle, UUID identity, issuer/number, versioning, domain history, grant authorization | Reuse lifecycle ideas and shared invoice contract where possible; assess a source-type/owner extension before creating a second generic invoice engine | Current invoice requires a Commerce `Sale` and its Activity context. A hospital invoice is tied to patient/encounter and is not automatically a Commerce Sale. Do not fabricate a Sale to fit this API. |
| `PaymentApplication` + `domain_finance.Payment` | Persisted payment creation/transitions, payer/payee Persons, amount in integer minor units, currency, invoice/obligation links, provider/external references, versioning/history, approval/grant checks | Prefer this existing payment lifecycle for the hospital payment adapter if identity and authorization contracts can be mapped | It does not create Settlement or Ledger entries. Payment is operational evidence, not financial recognition. |
| `ObligationApplication` + `domain_finance.Obligation` | Persisted creditor/debtor obligation, invoice source, amount/currency, due date and lifecycle, version/history and authorization | Reuse for a hospital receivable/payable only after policy determines creditor/debtor and the authoritative source invoice | Obligation does not infer satisfaction from Payment; partial balances are not a complete accounting/aging solution by themselves. |
| `domain_finance.Settlement` and `FinancialOrchestrator` | Settlement matching checks and a governed recognition orchestration contract with approval, grant, connectivity, durable operation identity | Reuse the existing authority rather than implement a hospital-only ledger writer | No `SettlementApplication` class was found in the reviewed default-branch code search. The orchestration path inspected accepts domain objects and returns a recognition result; this audit did not establish that it is wired end-to-end to persisted Payment/Settlement/Finance tables. Verify before depending on it. |
| `FinanceRecognitionCommand`, `FinancialTransaction`, `LedgerEntry` | Sole domain authority for recognized transaction/ledger-entry construction | Never duplicate or bypass this authority | The domain command alone is not evidence that the records were durably persisted. Persisted schema has stronger source references and uniqueness rules; the adapter must use the canonical persisted Finance boundary. |
| `FinancialAccountApplication` + `FinancialAccount` | Persisted account lifecycle, authorization, versioning and history | Reuse the account identity and lifecycle | A Financial Account is not a chart-of-accounts node and does not define debit/credit categories. Never guess the account from a hospital patient integer ID. |
| `FinancialBalanceReader` | Read-only balance derived from recognized transactions and posted entries for one account/currency | Reuse for a hospital-facing financial view after a valid account mapping exists | Do not calculate a competing persisted balance in the hospital database; balance is derived from the authoritative ledger. |
| `FinancialLedgerActivityReportReader` | Read-only, currency-specific account activity, opening/activity/closing totals | Reuse for account statements | It is not a trial balance, income statement, balance sheet, cash-flow statement, or bank reconciliation. |
| `FinancialOpeningBalanceApplication` | Governed opening-balance recognition with authorization and uniqueness | Reuse for approved opening balances only | Not a substitute for importing historical hospital invoices/payments; do not convert old records into opening balances without an approved migration/accounting policy. |
| `DurableOperationAuthority` and `idempotency.py` | Stable operation identity/fingerprint, replay/conflict handling primitives | Reuse for safe delivery/retry at the appropriate boundary | They do not automatically make cross-database effects atomic or exactly-once. Hospital SQLite and Finance persistence require an outbox/reconciliation design. |
| Existing AuthorizationGrant, AgentAction, Approval, domain history, provenance | Governed mutation, auditability and traceability patterns | Reuse established controls and test fixtures | Do not invent a weaker hospital-only path to reach Finance. |
| `SupplierInvoiceApplication` / purchase workflow | Supplier invoice evidence tied to recorded received quantities | Reuse supplier-side contracts where relevant | Supplier invoice evidence explicitly does not itself post ledger effects or mutate inventory. Do not conflate supplier invoices with patient invoices. |

## Key incompatibilities that block a direct class reuse

1. **Different invoice anchors:** Commerce Invoice requires a Sale; hospital invoice uses patient and optional encounter. Reuse requires a documented extension or an explicit hospital billing source contract, not a fabricated Sale.
2. **Identity mismatch:** hospital records use integer IDs; Finance uses UUIDs and persisted Person references. Add explicit stable mappings; never cast or infer IDs.
3. **Authorization mismatch:** hospital routes use hospital roles/tokens; Finance writes require persisted AgentAction, AuthorizationGrant, and human approval. A role check alone is not a Finance grant.
4. **Payment is not settlement:** the Finance model deliberately separates Payment, Settlement, Obligation, FinancialTransaction, and LedgerEntry. A received hospital payment must not directly post to the ledger.
5. **Partial payments:** hospital invoices permit multiple partial payments; the inspected in-memory `FinancialOrchestrator` rejects partial settlement until remaining obligation amounts are tracked. A policy and persisted remaining-balance model are required before automation.
6. **Persistence boundary:** the hospital uses a separate `HospitalDB`; Finance uses `connect_database/apply_migrations` and its relational schema. Their commits cannot be one atomic transaction. Use a durable outbox/mapping plus retries and reconciliation.
7. **Accounting completeness:** current Finance ledger is account/currency balance oriented. The repository's reporting-scope record explicitly says a complete chart-account mapping and double-entry debit/credit model are not established. Do not claim a general ledger by account, trial balance, income statement, balance sheet, or bank reconciliation yet.

## Recommended target architecture

Keep the hospital as the clinical/billing source for patient and encounter facts. Keep Finance as the sole authority for financial obligations, payments, settlements, recognition, and balances.

Proposed flow (not implemented by this audit):

1. Hospital creates an invoice/payment in its existing workflow and records a durable integration event/outbox item in the same HospitalDB transaction.
2. An adapter resolves the hospital patient to a persisted Finance Person and approved account/party mapping; unresolved or ambiguous mappings stop for reconciliation.
3. Adapter invokes existing Finance application services with the required approved AgentAction, active AuthorizationGrant, and human approval. It must not call `FinanceRecognitionCommand` as a shortcut around the governed boundary.
4. Finance returns durable identifiers. The adapter stores a unique source-event mapping (source system + entity type + source ID + event/version) to Finance invoice/obligation/payment/settlement/transaction/ledger IDs.
5. Retries use the same operation identity. Same key/same payload replays the recorded outcome; same key/different payload becomes a conflict. Failed events remain visible for reconciliation.
6. Hospital screens read Finance status/balances through a read-only projection/API; they do not maintain a second financial balance.

## Safe implementation order

1. Approve accounting semantics: invoice issuance vs cash receipt, receivable treatment, accounts, currency rules, void/refund/reversal behavior, and partial payments.
2. Verify the **persisted** Finance workflow from Payment through Settlement to durable FinancialTransaction/LedgerEntry; close any missing application/persistence gap before connecting Hospital.
3. Define Person/account/issuer mapping and hospital-specific authorization actions under the existing governance contract.
4. Add additive source-mapping/outbox schema and read-only reconciliation report. Preserve all current hospital and Finance IDs/history.
5. Implement the smallest adapter behind a disabled-by-default feature flag.
6. Test fresh and populated database migrations, partial and full payments, duplicate delivery, conflicting retries, crash windows, authorization failures, currency mismatch, void/refund/reversal, and reconciliation.
7. Enable only after CI passes on the exact head and finance owners approve the accounting policy.

## Files reviewed

- `hospital/core.py`, `hospital/api.py`, `hospital/security.py`
- `src/agent_core/domain_commerce.py`, `invoice_application.py`
- `src/agent_core/domain_finance.py`, `payment_application.py`, `obligation_application.py`
- `src/agent_core/financial_orchestration.py`, `financial_account_application.py`
- `src/agent_core/financial_balance_read.py`, `financial_ledger_activity_report.py`, `financial_opening_balance_application.py`
- `src/agent_core/integrity.py`, `src/agent_core/persistence.py`, `schema/migrations/0001_initial_relational_schema.sql`, `schema/migrations/0008_financial_opening_balance.sql`
- `docs/finance/financial-reporting-scope-20261017.md`, `docs/12-التنسيق-المالي/02-Source-of-Truth-Matrix.md`

## Explicit non-claims

This is a source-level architecture inventory, not a full test run or a production certification. No runtime code or database was changed, no hospital data was migrated, and no ledger entries were created. Class reuse opportunities are identified, but the persisted end-to-end Finance settlement/recognition path still needs verification before a live integration is safe.
