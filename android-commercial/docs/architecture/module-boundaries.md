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
| `CommercialCloudRepository.kt` | Shared context/token/paging helpers plus inventory, master data, supplier payments, returns, receivables, customer portal/bookings, accounting reads, and warehouse balances | `SupabaseClient.kt` owns HTTP transport; `CloudErrorMapper.kt` owns backend errors; `SalesRepository.kt` owns sales; `CustomerPaymentRequestsRepository.kt` owns payment-request workflows; remaining domains move to repositories under `data/repository/` |
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
