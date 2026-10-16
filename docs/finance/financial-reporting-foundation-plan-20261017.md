# Financial Reporting Foundation — Dependency-First Plan

**Date:** 2026-10-17  
**Scope:** Reporting prerequisites and implementation gates; no authorization to start Stage 12B or create a second ledger.

## Decision

Do not build statutory or management financial statements on top of the current account-level ledger activity view. First build and verify the accounting structures each report requires. The current Financial Account is an operational money/account container, not automatically a chart-of-accounts node.

## Dependency graph

```text
Canonical account ownership inventory
        |
        v
Governed Chart of Accounts (stable IDs, scoped codes, class, parent, postability)
        |
        v
Explicit source-to-account mapping policy
        |
        v
Balanced journal/posting contract (debit/credit lines, currency, period, source, reversals)
        |
        v
Posted journal/ledger integrity and audit diagnostics
        |
        +--> Trial balance
        |       +--> Income statement
        |       +--> Balance sheet
        |       +--> Retained earnings / period close policy
        |
        +--> Cash/bank classification + external statement lines
        |       +--> Cash-flow statement
        |       +--> Bank reconciliation
        |
        +--> Obligation allocation + remaining-balance model
        |       +--> Receivables/payables aging
        |
        +--> Approved FX rate source + rate purpose/date + rounding policy
                +--> Multi-currency consolidated reports
```

This graph is a dependency model, not a claim that these features already exist.

## Foundation A — canonical account inventory

Before persistence changes, inspect all current financial account, payment, settlement, obligation, instrument/voucher, opening-balance, ledger, audit, authorization, and migration contracts. Preserve the existing owners and IDs. Reuse the existing account lifecycle; do not create a second financial-account table.

The chart-of-accounts contract already defines intended account attributes and constraints in smart-chart-of-accounts.md. Before wiring it into posting, resolve:
- organization/activity/tenant scope supported by existing data;
- whether an account code is unique per chart, activity, or organization;
- stable identity and archived-account reference behavior;
- how current financial accounts map to chart accounts, if at all;
- the approved default chart template and localization expectations.

Do not guess these values from example code ranges.

## Foundation B — source-to-account mapping

Every financial source type that will affect statements needs an explicit, versioned mapping rule or an explicitly configured mapping:
- opening balance;
- recognized settlement/payment effect;
- reversal and correction;
- revenue and expense recognition;
- receivable/payable or customer/supplier advance effects;
- inventory and cost-of-goods-sold effects where supported.

Mapping must not infer cash movement from a saved Payment, nor revenue from a completed Sale without an approved recognition policy. Missing or ambiguous mapping must block posting/report classification and produce a diagnostic, not silently route to a suspense or guessed account unless that policy is explicitly approved.

## Foundation C — balanced posting contract

A reportable journal needs immutable journal identity and lines with:
- chart account ID;
- debit and credit minor-unit amounts, or an equally explicit signed representation with a validated normal-balance convention;
- currency code;
- source type and immutable source reference;
- effective/accounting date and recorded timestamp;
- scope, provenance, actor/approval evidence as required by existing authorization contracts;
- reversal/correction lineage;
- posting state and period/close constraints.

Enforce at database/application boundaries:
- every posted journal balances per currency under the approved policy;
- debit/credit values are non-negative if represented separately and cannot both be nonzero on one line;
- posting accounts must be active and postable;
- no silent edits/deletions of posted history;
- reversals/corrections preserve the original and have a traceable approved effect;
- idempotent retries cannot duplicate a posted effect;
- one failed operation leaves no partial journal.

Do not implement this as a second ledger. Before schema design, determine whether existing ledger_entries can safely be extended or whether a clearly documented journal layer is required.

## Foundation D — accounting periods and policy

Before period-based statements, define:
- accounting date versus event/recognition/posting timestamp;
- timezone and cutoff policy;
- open/closed period behavior and authorized reopening;
- treatment of late-arriving transactions;
- opening balances, closing balances, retained earnings, and comparative periods;
- rounding, precision, and negative-balance policy;
- whether statements are cash-basis, accrual-basis, or explicitly mixed and limited.

Never let report query timestamps silently substitute for an approved accounting date.

## Foundation E — obligations and settlement allocation

Aging reports require authoritative remaining balances. The current safety boundary rejects untracked partial settlements; preserve it until a persisted allocation/remainder model is explicitly designed. The future model must define:
- original obligation amount and currency;
- one or more settlement allocations with exact amounts;
- remaining amount derived from immutable allocations;
- due dates, dispute/void/cancel behavior, reversals, overpayments, and unapplied receipts;
- allocation constraints that prevent cross-currency or over-allocation;
- idempotency, authorization, audit, and historical corrections.

Until that exists, do not publish an accounts-receivable/payable aging report as if it were authoritative.

## Foundation F — bank/cash evidence and FX

Bank reconciliation requires imported or explicitly recorded external statement lines, stable source IDs, duplicate-import detection, matching rules, unmatched-item handling, and an audited reconciliation state. Internal ledger entries alone do not establish bank confirmation.

FX reporting requires a governed rate source, rate timestamp, quote direction, rate purpose (transaction, closing, or average), conversion precision, rounding, and realized/unrealized difference policy. Never combine different currency minor-unit amounts or convert them using a hard-coded or implicit rate.

## Acceptance gates

1. **Inventory gate:** current schema, owners, migrations, and all finance branches reconciled with evidence.
2. **Policy gate:** chart scope, posting rules, recognition mapping, accounting date, and report formulas written and reviewed.
3. **Persistence gate:** only after confirming there is no reusable canonical structure; additive migration and populated-database diagnostic required.
4. **Integrity gate:** balanced posting, idempotency, authorization, reversals, and corruption-detection tests pass.
5. **Report gate:** hand-calculated fixtures, period boundaries, currency isolation, and cross-report reconciliations pass.
6. **Release gate:** targeted and full CI pass; exact commit/run evidence recorded; unresolved limitations stated.

## Immediate permitted work

The account-level activity report can proceed as a read-only, explicitly limited report, provided CI passes and its source-history validation is correct. Work on trial balance, income statement, balance sheet, cash-flow statement, aging, reconciliation, or consolidated FX must stop at the relevant gate until its prerequisite model is implemented and tested.

## Out of scope

This plan does not authorize Stage 12B, provider/bank integrations, a second ledger, autonomous financial authority, automatic repair of financial history, or production-readiness claims.
