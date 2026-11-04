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
| `ReportsCenterScreen.kt` | Report catalog, report data builders, filters, report UI, PDF printing, XLSX generation, accounting/report aggregation | `feature/reports/`, `domain/calculation/`, `core/export/` |
| `CatalogScreen.kt` | Catalog search, market context, nearby discovery, price suggestions, category/unit/product creation, invoice-purchase linking, catalog transfers, ruling review | `feature/catalog/` plus catalog repository/data adapters |
| `ReportCalculations.kt` | Pure calculation models and functions for profitability, statements, aging, daily sales, inventory and performance | Split by calculation domain under `domain/calculation/`; preserve formulas and tests |
| `ActivityProfileScreen.kt` | Authentication/profile onboarding and business activity setup | `feature/settings/` or `feature/onboarding/`, backed by `core/auth/` and profile repository |

## Existing domain screens to preserve and group

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
- `ReportsCenterScreen.kt` retains data loading and state ownership, and passes the existing ledger projections and load errors to the validator.
- Validation precedence, Arabic feedback, required mapping/role keys, date cutoff, and per-entry/per-currency balance checks are preserved. The validator does not fetch data, mutate state, or calculate statement totals.
- `ReportLedgerValidationTest.kt` covers the failure gates and the valid balanced-ledger path.


### Catalog screen presentation extraction — 2026-11-04

- `CatalogScreenSections.kt` owns the category editor, unit editor, and transfer-request presentation.
- `CatalogScreen.kt` continues to own authentication, network requests, state transitions, discovery, product authoring, and invoice purchase workflows.
- The extracted sections receive state holders and callbacks; they do not own transport or business authorization.
