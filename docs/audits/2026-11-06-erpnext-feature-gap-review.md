# ERPNext feature-gap review for متجري — 2026-11-06

## Scope and method

Compared the user's ERPNext repository `adnanalhasani2020/erpnext` (default branch `develop`) with the current `main` branch of `adnanalhasani2020/Adnanalhasani-`, focusing on the Android commercial app, its navigation/screens, and Supabase migration inventory.

This is a feature/workflow comparison, not a claim of line-by-line equivalence. ERPNext is a large Frappe application (Python/JavaScript with MariaDB); متجري is Kotlin/Jetpack Compose with Supabase/PostgreSQL and a local/offline path. ERPNext source files and DocType implementations are not drop-in compatible. This plan adapts domain workflows to the existing app and database architecture rather than copying incompatible framework code. ERPNext is GPL-3.0; any literal source-code reuse would require a separate license/compliance review.

## Existing capabilities in متجري — do not duplicate

The current app already contains substantial implementations for:

- POS and sales: sale composition, checkout, multi-line sales, unit-aware quantities, sales history, returns and refund workflow.
- Purchasing: purchase orders, receiving, supplier invoices, supplier payments, purchase-line/catalog matching and due dates.
- Inventory: products/catalog, categories, units, warehouse/branch context, stock alerts, receipts, stock-cost layers and storage transfers (newest migration exists on main).
- Customer and supplier balances: customer debts, collections, supplier obligations/payments, customer statements and aging.
- Expenses and vouchers, accounting mappings and journal/ledger reporting.
- Customer portal: customer orders, bookings, cancellations, supply requests, payment requests, order fulfillment and notifications.
- Organization/activity setup, personal account delegation, roles/membership checks and reporting center.

A feature being present in code does not imply that every workflow is production-ready or deployed. Existing release gates and database migration status remain authoritative.

## ERPNext domains that are not represented as complete user-facing modules in متجري

| ERPNext domain | ERPNext workflow concepts | متجري status | Adaptation recommendation |
|---|---|---|---|
| CRM | Leads, prospects, opportunities, sales stages, lost reasons, appointments, campaigns, contracts | Missing as a dedicated CRM pipeline; customer records and sales history already exist | Add lead capture and qualification, convert qualified leads to existing customers without duplicates, opportunity pipeline, next-action reminders and sales activity history |
| Fixed assets | Asset register, categories, purchase linkage, locations, custodians, depreciation books/schedules, movements, value adjustments, disposal | Missing | Add asset register and custody/location movement first; then maintenance, depreciation and disposal with accounting integration |
| Asset maintenance | Maintenance plans, tasks, teams, maintenance logs, repairs and consumed parts | Missing | Add maintenance schedule and service history after asset register |
| Projects and tasks | Projects, task dependencies, task types, progress, milestones, timesheets, costing and budgets | Missing as a project module; personal work notes are not project/task management | Add projects, assigned tasks, deadlines, dependencies and progress; only add timesheet costing after accounting rules are defined |
| Manufacturing | BOM, production plan, work orders, job cards, workstations, operations, material consumption, downtime | Missing | Optional vertical module; do not add to the general store workflow unless manufacturing users are in scope |
| Quality management | Quality goals/procedures, inspections/feedback, non-conformance, corrective actions and reviews | Missing | Optional module; start with receipt/production quality checks only if required by target businesses |
| Support/service desk | Issues, service requests, assignment and follow-up | Missing as a dedicated service desk | Add lightweight customer issue/ticket tracking linked to existing customer/order records |
| E-commerce storefront | Web catalog, cart and customer self-service | Partial | Existing customer portal/order flow is present; compare remaining checkout, pricing, delivery and storefront requirements before expanding |

## Recommended implementation order

1. **CRM Lite** — highest general commercial value. Lead → qualification → opportunity → quotation/follow-up → conversion to existing customer. Reuse current customer, organization, activity, sales and notification infrastructure. Prevent duplicate customers and keep CRM activities separate from financial ledger entries.
2. **Fixed asset register** — asset categories, asset code, purchase source, purchase/available-for-use dates, cost, location, custodian, status and attachments. Link purchase invoices where a reliable source relation exists. Do not post depreciation until finance-book and journal-entry rules are implemented and tested.
3. **Asset movement and maintenance** — custody/location transfer history, maintenance schedules, work logs, repair costs and parts usage.
4. **Projects and tasks** — project status, start/end dates, task ownership, dependencies, progress and activity history. Link billable/costed work to existing finance only after explicit posting rules.
5. **Support tickets** — customer issue, priority, status, assignee, response history and links to sales/orders.
6. **Manufacturing and quality** — optional vertical extensions; build only with explicit sector requirements and inventory-costing acceptance tests.

## Cross-cutting requirements for every adapted module

- Respect organization, activity and branch/location context; no cross-tenant reads or writes.
- RLS/RPC permissions must be reviewed with authenticated positive and negative tests.
- Use append-only history for status changes, transfers, approvals and accounting-impacting actions.
- Use idempotency keys for operations that may be retried.
- Keep operational records separate from posted financial records; never create a ledger posting from a draft or unapproved workflow.
- Preserve Arabic RTL, existing navigation, offline/local mode, and current cloud fail-closed behavior.
- Add schema migrations, Kotlin repository/model/screen, navigation wiring, contract tests and release gates together.
- Never remove or replace an existing capability to make room for a new module.

## First implementation slice

Start with **CRM Lite** because it applies to most stores and reuses existing customers and sales workflows. The first release should include:
- Leads with name/business, phone, optional email, source, assigned user, status, notes and next follow-up date.
- Qualification and conversion into an existing customer with duplicate detection.
- Opportunities linked to a lead/customer, expected value, probability, expected close date, stage and loss reason.
- Activity history (call/visit/message/note) and due follow-ups.
- A compact pipeline summary/report.

Do not include marketing automation, bulk email, external messaging integrations or AI scoring in the first slice.

## Acceptance gates

- A lead can be created, edited, assigned, searched and moved through valid states.
- Conversion cannot create a duplicate customer when a matching account is already linked.
- Opportunities and activities remain scoped to the active organization/activity.
- Users without CRM permissions cannot read or mutate records.
- A retry does not duplicate a conversion or activity.
- Existing sales, purchasing, inventory, customer balance, reporting and offline flows remain unchanged.
- CI passes for Python contracts and Android build/tests; database migrations replay cleanly in an isolated test database.
- Production deployment remains separate and requires explicit migration verification.

## Source reference

- Source repository: https://github.com/adnanalhasani2020/erpnext (default branch `develop`)
- ERPNext root modules inspected: accounts, stock, buying, selling, assets, manufacturing, projects, crm, quality_management, support and maintenance.
- Relevant ERPNext concepts inspected include Asset, Lead and Project DocTypes. This document describes adapted behavior, not copied source code.
