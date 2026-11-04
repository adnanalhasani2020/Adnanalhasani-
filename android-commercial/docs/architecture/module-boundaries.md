# Existing code inventory and target ownership

Baseline inspected at commit `b15ce71cd0066a3fd2eecc8195338767bc6f19f3` on `fix/cloud-context-activation-fail-closed`.

This document describes the existing code, then records where responsibility should live after refactoring. Target names are proposed destinations, not claims that those files already exist.

## Extracted model ownership

- `CommercialModels.kt` now owns the shared `Product` and `SaleRecord` models because both the cloud repository and Compose screens consume them.
- `LocalStoreModels.kt` now owns local-only `SettlementRecord`, `ExpenseRecord`, and local sale-unit parsing helpers. The helper visibility is package-internal so the existing MainActivity screens can keep calling the same functions.
- `LocalStoreRepository.kt` now owns the existing SharedPreferences keys, organization-scoped key selection, and JSON load/save routines for products, sales, settlements, and expenses. The JSON field names, preference file name, fallback behavior, and `.apply()` persistence semantics were preserved.
- `DashboardScreen.kt`, `LocalSalesScreen.kt`, and `LocalInventoryScreen.kt` now own their respective local feature screens. `StoreUiComponents.kt` owns shared metric/header/product/sale rows; `StoreUiTheme.kt` and `StoreUiFormatting.kt` own the shared local UI palette and legacy currency formatter.

## Large mixed-responsibility files

| Existing source | Observed responsibilities | Target ownership |
|---|---|---|
| `MainActivity.kt` | Activity entry, app composition, navigation/state, local screens/dialogs, cloud report entry | `app/`, `ui/navigation/`, `feature/dashboard/`, and the matching feature packages. Shared/local models now live in `CommercialModels.kt` and `LocalStoreModels.kt`; SharedPreferences persistence now lives in `LocalStoreRepository.kt`. |
| `CommercialCloudRepository.kt` | Shared context/token/paging helpers, customer options, and allowlisted operational record reads | `SupabaseClient.kt` owns HTTP transport; `CatalogRepository.kt`, `MasterDataRepository.kt`, `InventoryRepository.kt`, `SalesRepository.kt`, `PurchaseRepository.kt`, `SupplierPaymentRepository.kt`, `ExpenseRepository.kt`, `ReturnsRepository.kt`, `CustomerPortalRepository.kt`, `CustomerAccountsRepository.kt`, and `AccountingRepository.kt` own extracted domains |
| `ReportsCenterScreen.kt` | Report selection, date/filter state, row orchestration, report UI, export actions | `feature/reports/`, `domain/calculation/`, `core/export/`; cloud projections now belong to `ReportsCloudData.kt` |
| `CatalogScreen.kt` | Catalog search, market context, nearby discovery, price suggestions, category/unit/product creation, invoice-purchase linking, catalog transfers, ruling review | `feature/catalog/` plus catalog repository/data adapters |
| `ReportCalculations.kt` | Pure calculation models and functions for profitability, statements, aging, daily sales, inventory and performance | Split by calculation domain under `domain/calculation/`; preserve formulas and tests |
| `ActivityProfileScreen.kt` | Authentication/profile onboarding and business activity setup | `feature/settings/` or `feature/onboarding/`, backed by `core/auth/` and profile repository |

| `ReportsCloudData.kt` | Immutable cloud report projections, per-source load errors, and refresh lifecycle | Keep data retrieval and refresh ownership outside Compose report presentation; preserve existing repository query scopes and fail-closed source errors |\n\n## Existing domain screens to preserve and group

### Sales
- `CommercialCloudSalesScreen.kt`
- `CommercialCloudSaleComposerScreen.kt`

### Inventory and catalog
- `CatalogScreen.kt`
- `CatalogProductEditDialog.kt`
- `CatalogDeviceLocation.kt`
- `CommercialCloudStockAlertsScreen.kt`
- `CommercialUnitDirectoryScreen.kt`
- `CommercialQuantityMath.kt`
- `CommercialReturnQuantityFormatting.kt`

### Purchases and suppliers
- `CommercialCloudPurchasesScreen.kt`
- `PurchaseOrderEntryDialog.kt`
- `PurchaseReceiptEntryDialog.kt`
- `PurchaseLineOfferingLinkDialog.kt`
- `SupplierInvoiceApplication.kt`
- `SupplierInvoiceEntryDialog.kt`
- `SupplierPaymentEntryDialog.kt`

### Customers and customer portal
- `CommercialCloudDebtScreen.kt`
- `CustomerPortalScreen.kt`
- `CustomerPortalHistoryScreen.kt`
- `CustomerPortalPaymentRequestsScreen.kt`
- `CustomerOrderFulfillmentScreen.kt`
- `CustomerReceivableAgingReport.kt`

### Returns, expenses, accounting, and master data
- `CommercialCloudReturnsScreen.kt`
- `CommercialCloudReturnRefund.kt`
- `CommercialCloudExpensesScreen.kt`
- `CommercialCloudOperationalScreen.kt`
- `PersonalAccountsScreen.kt`
- `MasterDataScreen.kt`
- `CommercialCloudReleaseGates.kt`

### Reports and notifications
- `ReportsCenterScreen.kt`
- `ReportCalculations.kt`
- `ReportAgingCalculations.kt`
- `SupplierDueDateAgingReport.kt`
- `CommercialNotificationsScreen.kt`

## Non-negotiable dependency direction

```text
Compose UI
   -> feature state / use cases
      -> domain rules and calculations
         -> repository interfaces
            -> data implementations
               -> shared network/auth transport
                  -> Supabase RPC / PostgREST
```

The UI must not become the authority for tenant access, balances, prices, posting, idempotency, or permissions. Those guarantees remain enforced by database policies and server-side RPCs.

## Compatibility constraints

- Keep the existing application ID, package identity, build configuration, and manifest behavior.
- Preserve Supabase table names, RPC names, parameter names, response contracts, and migration order.
- Preserve idempotency keys across retries.
- Preserve exact money minor-unit handling and BigDecimal quantity conversions.
- Preserve local/cloud separation and fail-closed behavior.
- Do not introduce a second source of truth for products, offerings, units, locations, balances, or ledger entries.
- Do not relocate source files until source-contract tests and build configuration have been checked for path-sensitive assumptions.


## Reports extraction progress — 2026-11-04

- Completed: `ReportsCatalog.kt` owns the report catalog model and catalog builder.
- Completed: `ReportInventoryLines.kt` owns the common report row model and pure inventory report mapping for location balances and stock movements by user.
- Still in `ReportsCenterScreen.kt`: report data loading, filter state, report selection, financial/customer/supplier calculations orchestration, Compose rendering, and export integration.
- Do not move calculation rules into Compose UI. Keep reusable calculations in `ReportCalculations.kt` or focused pure calculation files; keep PDF/XLSX/print rendering in `ReportExport.kt`.


- `ReportFinancialLines.kt` owns pure row assembly for cash-flow and tax/discount summaries. It may consume the internal report date/money formatters but must not load data or own Compose state.


- `CatalogActivityHierarchy.kt` owns the pure activity hierarchy model and selector helpers. `CatalogScreen.kt` remains the UI/data orchestration owner; do not move RPC calls or Compose state into the hierarchy helper.


- `CatalogModels.kt` owns catalog-domain data structures shared by catalog UI/dialogs. `CatalogScreen.kt` owns screen state and workflow orchestration; `CatalogActivityHierarchy.kt` owns pure activity-tree selection helpers; `CatalogRepository.kt` owns catalog inventory/offering data access. Keep global catalog items, organization offerings, and local stock as distinct concepts.


- `CatalogCloudTransport.kt` is a thin catalog adapter over the one shared `SupabaseClient`; it must not create a second HTTP stack or session preference store. Catalog calls keep their existing RPC names, payloads, tokens, and organization/location filters.


- `ReportDomainMappers.kt` owns pure conversions and row projection between cloud document models, report calculation inputs, and `ReportLine` values. It must not load data, own Compose state, or introduce business formulas; those remain in the domain calculation files.


- `ReportFormatting.kt` owns pure date/timestamp and currency display formatting. Report UI and export callers may reuse it; it does not load data or perform financial aggregation.


- `ReportLedgerClassification.kt` owns pure ledger account classification and account-net aggregation. It does not fetch journal data, validate ledger balance, choose currencies, or render report rows.


- `CatalogLoginPanel.kt` is presentation-only. Authentication requests, access/refresh token handling, encrypted preference writes, and session restoration remain in the catalog screen's workflow and shared session utilities.


- `LocalProductDialogs.kt` owns presentation and form state for local sale/product dialogs. Persistence and parent navigation/state remain with the existing application workflow and `LocalStoreRepository`.


- `MainActivity.kt` is limited to Android startup and application theme composition. Local operational screen rendering lives in `LocalOperationalScreens.kt`; local sale/product form dialogs live in `LocalProductDialogs.kt`. Shared app state and persistence remain in `StoreApp.kt` and `LocalStoreRepository.kt`.


### Catalog market data boundary

The catalog screen owns interaction and request orchestration. JSON-to-domain mapping for market branches/warehouses, nearby discovery, and price suggestions belongs to `CatalogMarketData.kt`. This keeps response parsing independently testable without moving location permissions, pagination, or state transitions out of the screen.


### Catalog discovery product mapping boundary

`CatalogProductMapper.kt` owns conversion of the paginated catalog-discovery JSON response into the existing `CatalogProduct` model, including localized display fallbacks and nested unit/ruling data. `CatalogScreen.kt` retains the RPC request, pagination, de-duplication, loading/error state, and user feedback. The mapper must not change the catalog RPC contract or conflate global catalog items with organization offerings and local inventory.


### Catalog directory response boundary

- `CatalogDirectoryMapper.kt` owns pure JSON-to-domain mapping for catalog categories, category translations/descriptions, global units, localized unit names, local unit-code membership, and business activity hierarchy nodes.
- `CatalogScreen.kt` retains all directory network calls, locale/query selection, initial activity inference, Compose state updates, feedback, and follow-up market-context loading. The mapper must not issue requests or change the directory RPC/REST response contract.


### Catalog workflow response boundary

- `CatalogWorkflowMappers.kt` owns pure JSON mapping for invoice-purchase request rows and pending catalog-transfer rows.
- `CatalogScreen.kt` retains the request endpoints, organization scoping, ordering/limits, action orchestration, refreshes, and feedback. Mapping extraction must not change approval/rejection actions or invoice-to-purchase linking semantics.


### Catalog invoice-mapping response boundary

- `CatalogInvoiceMappingMapper.kt` owns only the final prepared response-to-model conversion for location, warehouses, unmatched purchase lines, and candidate offerings.
- `CatalogScreen.kt` retains all purchase-order/supplier-invoice validation, REST orchestration, active offering/product filtering, candidate eligibility, selected mapping state, and feedback. Do not move or relax those business checks as part of this parser extraction.


### Reports ledger-validation boundary (2026-11-04)
- `ReportLedgerValidation.kt` owns the pure fail-closed validation that gates financial statement rendering: cloud-mode requirement, source-load errors, required GL/supplier mappings, posted-entry dates, balanced entries by currency, known account types, and ISO currency codes.
- `ReportsCenterScreen.kt` retains report calculation orchestration and passes the existing ledger projections and load errors to the validator. Cloud projections are owned by `ReportsCloudData.kt`; interactive Compose state is owned by `ReportsCenterUiState.kt`.
- Validation precedence, Arabic feedback, required mapping/role keys, date cutoff, and per-entry/per-currency balance checks are preserved. The validator does not fetch data, mutate state, or calculate statement totals.
- `ReportLedgerValidationTest.kt` covers the failure gates and the valid balanced-ledger path.


### Catalog screen presentation extraction — 2026-11-04

- `CatalogScreenSections.kt` owns the category editor, unit editor, and transfer-request presentation.
- `CatalogScreen.kt` continues to own authentication, network requests, state transitions, discovery, product authoring, and invoice purchase workflows.
- The extracted sections receive state holders and callbacks; they do not own transport or business authorization.

- `CatalogProductFormState.kt` owns product-authoring form state; `CatalogScreenSections.kt` owns the product form presentation. The existing create-product action remains in `CatalogScreen.kt`.

- `CatalogInvoiceTransferState.kt` owns the invoice-review and matching presentation state; `CatalogScreenSections.kt` owns the invoice-to-purchase review section. Existing action functions and RPC calls remain in `CatalogScreen.kt`.


### Reports cloud data boundary — 2026-11-04
- `ReportsCloudData.kt` owns cloud report loading, the immutable projection snapshot, and source-specific error state. It calls the existing repository loader functions only; it does not introduce a new transport or alter organization scoping.
- `ReportsCenterScreen.kt` consumes the snapshot through stable local aliases and retains date/filter state, report calculation orchestration, selected-row filtering, UI, and export integration.
- A failed source continues to produce an empty projection and a source-specific error. Do not present stale rows as freshly loaded data or make an unsupported report selectable.


### Reports center UI state boundary — 2026-11-04
- `ReportsCenterUiState.kt` owns mutable interactive state for period selection, search and row filters, selected report/category, export metadata, feedback, supplier filter, and refresh key.
- `ReportsCenterScreen.kt` consumes those holders through delegated local properties. Do not move report formulas, repository calls, or export side effects into the state class.


- `ReportsPeriodControls.kt` owns only the period-preset and manual date-range controls. Date validation, date-window calculations, and report filtering remain in `ReportsCenterScreen.kt`/report domain helpers.


- `ReportsExportLaunchers.kt` owns Android document-picker lifecycle, stream writing, sharing, and the date/source guards for export actions. `ReportExport.kt` owns the actual PDF/XLSX/print renderers; the screen owns export-row selection and metadata.


- `ReportsCatalogSection.kt` owns only the report category/catalog presentation and title-selection mapping. It consumes `ReportCatalogItem` and source-readiness inputs; catalog definitions remain in `ReportsCatalog.kt`, while report data and calculations remain outside the UI section.


- `ReportInventoryRows.kt` owns row assembly for inventory-specific report selections. It consumes already-loaded projections and returns `null` for report families it does not own; it does not load data, mutate inventory, or change cost/quantity rules.


- `ReportLedgerRows.kt` owns selected-row assembly for posted-ledger financial statements and ledger activity reports. It consumes validated ledger projections and report filters; it does not fetch or post journal entries or relax ledger validation.


- `ReportPartyStatementRows.kt` owns customer/supplier statement row assembly from already-loaded party-account and posted-ledger projections, plus the operational customer statement. It does not perform collections, supplier payments, or ledger posting.
- `ReportPartyStatementValidation.kt` owns fail-closed source/link validation for full customer and supplier control-account statements. It preserves source-error precedence and mapping/currency/party-link checks; it does not assemble rows or load data.
- `ReportTaxValidation.kt` owns fail-closed source validation for the detailed recorded-tax/discount report, including source errors, valid dates, amount/currency integrity, and return-to-sale currency matching. It does not calculate tax or build presentation rows.


- `ReportSalesRows.kt` owns pure row assembly for sales-period, invoice-detail, and period-comparison reports. It consumes existing sales projections and date boundaries; it does not load sales, mutate invoices, or calculate authoritative balances.


- `ReportSupplierRows.kt` owns supplier report row assembly from loaded purchase, invoice, obligation, and posted-payment projections. It does not create supplier payments or alter purchase/invoice lifecycle state.

- `ReportSupplierValidation.kt` owns fail-closed supplier invoice source, amount/currency, purchase-line/subtotal/tax/freight reconciliation, and obligation-integrity validation. It delegates price-variance and obligation arithmetic to the existing calculation owners and does not build report rows.


- `ReportOperationalRows.kt` owns display-row assembly for payment, settlement, return, and expense reports. It is read-only and must not initiate collections, refunds, expense cancellation, or journal posting.


- `ReportDerivedRows.kt` maps existing domain calculation/report states into `ReportLine` rows. It does not calculate totals, load source data, or relax validation errors.


- `ReportTaxRows.kt` owns presentation rows for detailed recorded taxes/discounts from existing sales, supplier invoices, and completed returns. It does not calculate tax rates or a net tax liability.


- `ReportProfitabilityRows.kt` owns row presentation for item profitability and historical cost/profit. Calculation formulas remain in their domain calculation files; the mapper must not substitute current purchase costs for historical sale-line costs.

- `ReportProfitabilityValidation.kt` owns fail-closed source and historical-cost integrity checks for the cost/profit report. It verifies recorded sale costs, return-cost prerequisites, original-sale/currency linkage, tax-return limitations, and exact total arithmetic; it does not assemble rows or replace the calculation owner.


- `ReportsResultSection.kt` owns read-only result cards, empty-state presentation, and export/print action composition. It consumes already-filtered rows and uses callbacks for export state and feedback; file creation/share lifecycle and rendering stay in their dedicated owners.


- `ReportsFilterSection.kt` owns only report filter controls. It does not apply filters or widen report queries; the screen/report row pipeline remains responsible for filtering and ledger account scoping.


### Reports center coordinator boundary — 2026-11-04
- `ReportsCenterScreen.kt` is the Compose composition root and event coordinator; it no longer owns report source loading, validation/calculation orchestration, report row construction, or document export implementation.
- `ReportsCenterData.kt` owns date/source validation, calculation orchestration, selected report dispatch, selected-row filtering, and summary totals. It is UI-free apart from Compose `remember` lifecycle.
- `ReportsCloudData.kt` owns cloud projection loading; `ReportsCenterUiState.kt` owns interactive state; `ReportsPeriodControls.kt`, `ReportsFilterSection.kt`, `ReportsCatalogSection.kt`, and `ReportsResultSection.kt` own UI sections; report-family row files own row mapping; export launchers/renderers remain separate.


### Reports operational validation extraction (2026-11-05)

- `ReportOperationalValidation.kt` owns fail-closed validation for operational expenses, payment-method analysis, operational customer statements, customer collections, user sales performance, and daily sales trends.
- `ReportProfitabilityValidation.kt` owns both historical cost/profit validation and item-profitability invoice/return integrity gates; calculation orchestration remains in `ReportsCenterData.kt`.
- `ReportsCenterData.kt` retains loaded source projections, calculation orchestration, and report-to-error routing; calculation formulas and row builders remain in their existing owners.
- The extraction preserves cloud-mode/source-error precedence, period-scoped checks where originally applicable, Arabic error messages, and exact-balance validation for operational customer movements.


### Reports financial source validation extraction (2026-11-05)

- `ReportFinancialValidation.kt` owns current-inventory valuation readiness and cash-flow source/integrity gates. Cash-flow validation preflights the exact subtraction, opening-balance, running-balance, and debit/credit movement arithmetic used by `ReportFinancialLines.kt`.
- `ReportsCenterData.kt` retains only report-title routing to the validation functions. Row assembly remains in `ReportInventoryRows.kt` and `ReportFinancialLines.kt`; no financial formulas or source queries were moved.


### Report calculation input mapping extraction (2026-11-05)

- `ReportDomainMappers.kt` now owns pure projections from saved cloud sales/returns into item-profitability, customer-collection, user-performance, daily-sales, and supplier-purchase-summary calculation inputs.
- `ReportsCenterData.kt` retains Compose `remember` lifecycles and calculation invocation; formulas, period predicates, and row assembly remain in their existing owners.
- The mapping preserves completed/succeeded status filters, date-window boundaries, all-sale-lines availability for historical return linkage, and original source field mappings.


### Supplier obligation input mapping extraction (2026-11-05)

- `ReportDomainMappers.kt` owns selection of active supplier invoice/obligation candidates and projection of obligations recorded before the selected period end into `SupplierObligationInput`.
- `ReportsCenterData.kt` retains the shared candidate list for supplier invoice validation and calculation lifecycle/orchestration. Cancellation-state rules, recorded-date cutoff, currency fields, and supplier fallback labels are preserved.


### Report currency summary exactness (2026-11-05)

- `ReportCalculations.kt` owns exact per-currency aggregation for the reports-center sales summary through `calculateReportCurrencyTotals`.
- `ReportsCenterData.kt` catches overflow and exposes a dedicated summary error; `ReportsCenterScreen.kt` suppresses wrapped totals and displays the error rather than presenting an incorrect amount.
- Currency buckets and the existing blank-currency fallback are preserved. No report filters or source queries are changed.


### Sales report aggregate exactness (2026-11-05)

- `ReportSalesRows.kt` uses exact `Long` aggregation for all sales summary groupings and exact subtraction for period comparisons; local quantity counts use exact `Int` aggregation.
- `ReportsCenterData.kt` preflights the selected sales row builder once, caches the result for dispatch, and surfaces a fail-closed error through the existing sales source-error route if any aggregate overflows.
- Local cash/credit report source behavior remains unchanged; its arithmetic overflow is routed independently from cloud-only sales source validation.
