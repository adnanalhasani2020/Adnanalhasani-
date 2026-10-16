# Smart Chart of Accounts — Domain Contract

**Status:** Design baseline; implementation must be reconciled with existing financial-account and voucher domains before activation.  
**Target repository:** `adnanalhasani2020/Adnanalhasani-`  
**Baseline:** `main` as inspected on 2026-10-16.

## 1. Scope and guardrails

The chart of accounts (CoA) is the governed classification of accounts used by financial workflows. It is not a substitute for the existing financial-account lifecycle, vouchers, currencies, obligations, or payment records.

This document deliberately does not introduce a second ledger, duplicate financial-account table, or a new posting engine. The current README says durable financial workflow / Stage 12B is deferred and unauthorized; this design must not silently activate it. Implement only after locating and reusing the canonical financial account, voucher, currency, and persistence contracts.

## 2. Required account model

Each account should have these logical attributes (map to existing naming conventions instead of duplicating columns):

- Stable internal ID (immutable; never derived from account code).
- Organization / tenant scope, where supported by the existing domain.
- Code, unique within its chart scope; stored as text to preserve leading zeroes.
- Arabic name and optional English name.
- Account class: `asset`, `liability`, `equity`, `revenue`, `expense`.
- Parent account ID (nullable only for root accounts).
- Account level / path derived from the parent chain, not independently editable.
- Posting policy: group/header or posting/detail.
- Normal balance: debit or credit, derived from class by default but overridable only through an explicit, validated policy if the accounting standard requires it.
- Active / archived state; archival must not erase historical references.
- System-managed marker and metadata for auditability.
- Created/updated timestamps and actor fields where the existing audit contract supports them.

Do not add balances as mutable fields on account definitions. Balances are derived from validated posted entries or existing authoritative financial records.

## 3. Baseline account taxonomy

Use a configurable, versioned template rather than hard-coded assumptions. The following is a starter taxonomy, not a claim that every organization must use every leaf:

| Class | Suggested code range | Example groups |
|---|---:|---|
| Assets | 1xxx | Cash and cash equivalents, bank accounts, receivables, inventory, prepayments, fixed assets, accumulated depreciation |
| Liabilities | 2xxx | Payables, accrued expenses, taxes payable, loans, customer advances |
| Equity | 3xxx | Capital, reserves, retained earnings, current-period result |
| Revenue | 4xxx | Sales, service revenue, sales returns/discounts as contra-revenue where supported |
| Expenses | 5xxx–6xxx | Cost of goods sold, payroll, rent, utilities, transport, depreciation, administrative expenses, finance costs |

Codes are configuration, not business logic. Do not assume a fixed number of digits or reserve ranges in code. Allow organizations to configure code length and hierarchy depth, with safe defaults.

## 4. Invariants that must be enforced

1. Account codes are unique within the correct organization/chart scope, using normalized comparison and preserving the displayed code.
2. A parent must exist in the same scope, cannot be the account itself, and cannot create a cycle.
3. Only detail/posting accounts can receive postings; group accounts are non-postable.
4. An account referenced by posted history cannot be physically deleted. Deactivate/archive it and preserve historical labels/IDs.
5. Inactive accounts cannot be selected for new transactions, except an explicitly authorized correction/reversal flow defined by the existing domain.
6. Parent and child account classes must be compatible; cross-class parentage is rejected.
7. Code, parent, class, and posting-policy changes must be validated atomically and audited.
8. Duplicate creation and retries must be idempotent where the repository's existing command/idempotency contract applies.
9. Monetary amounts must use the project's established decimal/minor-unit and currency rules; never use binary floating point for accounting arithmetic.
10. Do not treat a saved payment, obligation, or account record as proof that money moved or a journal was posted.

## 5. Smart capabilities

Build these as deterministic, explainable services over the canonical chart—not as an unconstrained AI writer:

- **Account-code assistant:** suggest the next available code under a selected parent, checking uniqueness and configured format.
- **Account classification assistant:** propose likely class/parent from the account name and purpose; require a human to confirm before mutation.
- **Duplicate detector:** normalize Arabic/English names, punctuation, whitespace, and common variants; return candidates with reasons and similarity scores. Never auto-merge accounts.
- **Completeness checker:** identify missing required groups, orphaned accounts, invalid levels, inactive parents with active children, and unused accounts (only where usage data is authoritative).
- **Change-impact preview:** show child accounts and known references before changing a parent, class, code, or status.
- **Natural-language search:** map user queries to ranked existing accounts, always showing code, name, class, and status; no direct writes from a search result.
- **Audit explanation:** describe why a proposed change passed or failed using invariant IDs and concrete references.

AI output must be treated as untrusted suggestions. Validate all proposed changes with domain rules, permissions, and database constraints.

## 6. API / application boundaries

Follow the repository's existing application-service and persistence patterns. Expected use cases, if not already present:

- list/search accounts with pagination and scope filtering;
- retrieve account and tree path;
- create account;
- update editable metadata;
- move account with cycle/class validation;
- activate/deactivate/archive;
- preview duplicate candidates;
- validate chart and produce a structured findings report;
- export a stable chart snapshot.

Each write should use the existing transaction boundary and authorization/audit conventions. Do not create a parallel database connection, migration framework, or service layer merely for the CoA.

## 7. Data migration and compatibility

Before adding schema or changing code:

1. Inventory all existing account, currency, voucher, obligation, payment, opening-balance, and journal-like tables/models.
2. Identify the canonical account identity and any current account-code/name fields.
3. Map existing records to the CoA without changing IDs or breaking foreign keys.
4. Detect duplicate codes, orphan references, cycles, and class mismatches in a read-only report.
5. Add database uniqueness / foreign-key / check constraints only after the current data passes or a reviewed repair migration exists.
6. Make migrations additive and reversible where feasible; never silently delete or rewrite financial history.
7. Confirm the behavior of old callers and tests before switching any read/write path.

No destructive migration should run automatically as part of application startup.

## 8. Required test matrix

- valid root and child creation;
- duplicate code within same scope and same code across separate scopes;
- leading-zero codes and configured code formats;
- missing parent, cross-scope parent, self-parent, and multi-node cycle;
- incompatible parent/child classes;
- group account posting rejection and detail account acceptance;
- archive/deactivate behavior with historical references;
- inactive account selection and authorized correction exception;
- concurrent attempts to create the same code;
- idempotent retry behavior;
- audit record creation and permission failures;
- Arabic normalization and duplicate suggestions with no automatic mutation;
- migration against existing populated data;
- regression tests for vouchers, currencies, obligations, payments, and existing financial-account lifecycle.

## 9. Definition of done

- Existing domain ownership and database schema are documented.
- One canonical account identity is reused; no parallel account table or posting engine exists.
- Constraints are enforced in both application logic and database where supported.
- All writes are scoped, authorized, transactional, and auditable.
- AI features are suggestion-only until a user confirms; deterministic validation remains authoritative.
- Migrations and regression tests pass on the target baseline.
- Documentation distinguishes implemented behavior from planned behavior.
- CI passes; results are reported with the exact commit and test count.
