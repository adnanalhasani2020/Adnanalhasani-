# Commercial application refactoring execution ledger

Baseline branch: `fix/cloud-context-activation-fail-closed`  
Baseline commit: `b15ce71cd0066a3fd2eecc8195338767bc6f19f3`  
Working branch: `refactor/commercial-code-organization`

## Execution policy

Work in small, independently reviewable slices. Every slice must:
1. Inspect current implementation and all call sites before editing.
2. Preserve existing features and public behavior.
3. Add or update focused tests.
4. Run the narrow tests, full relevant test suite, and Android build when available.
5. Record exact commit and test results.
6. Never claim a cloud workflow is verified without an authenticated integration test.

No database migration is required for source-only organization. Do not modify Supabase production data as part of this refactor.

## REF-001 baseline commands and path-sensitive tests

The Android project has no checked-in Gradle wrapper in `android-commercial/`. The repository CI installs Gradle 8.9 and JDK 17, so use the same commands locally or in CI:

```bash
# Android unit tests
gradle -p android-commercial --no-daemon testDebugUnitTest

# Debug APK
gradle -p android-commercial --no-daemon assembleDebug

# Focused report source contract (also exercises report export ownership)
python -m unittest discover -s tests -p "test_reports_center_source_contract.py"

# Full Python repository suite (CI installs pytest first)
python -m pytest -q tests hospital/tests
```

Workflow coverage inspected on this branch:
- `.github/workflows/android-commercial-build.yml`: builds the commercial debug APK on PRs touching `android-commercial/**`; it does not run unit tests.
- `.github/workflows/android-commercial.yml`: on PRs touching commercial source, the report source-contract test, Android unit tests, and APK build run.
- `.github/workflows/python-tests.yml`: runs `tests` and `hospital/tests` on PRs.

High-risk path-sensitive source contracts confirmed in `tests/` (this is a targeted list, not yet an exhaustive inventory):
- Main entry/auth/local flows: `test_commercial_first_launch_flow.py`, `test_customer_payment_request_android_contract.py`, `test_commercial_cloud_expense_vouchers.py`, `test_commercial_cloud_sales_screen_contract.py`, `test_commercial_cloud_operational_screen_contract.py`.
- Cloud repository and customer/payment flows: `test_customer_portal_order_fulfillment_contract.py`, `test_customer_portal_multi_service_booking_contract.py`, `test_commercial_repeated_sale_lines.py`, `test_commercial_invoice_source_of_truth.py`, `test_commercial_unit_directory_screen.py`, `test_commercial_cloud_stock_alerts_contract.py`.
- Extracted repository and local boundaries: `test_supabase_client_transport_contract.py`, `test_commercial_cloud_actionable_errors.py`, `test_customer_payment_request_android_contract.py`, `test_commercial_cloud_sales_screen_contract.py`, `test_commercial_cloud_expense_vouchers.py`, `test_financial_voucher_atomicity_hardening.py`, `test_local_store_repository_contract.py`, `test_local_commercial_screens_contract.py`.
- Reports/calculations: `test_reports_center_source_contract.py`, `test_item_profitability_report_contract.py`, `test_customer_collections_report_contract.py`, `test_customer_receivable_aging_report_contract.py`, `test_supplier_liability_aging_report_contract.py`, `test_tax_discounts_summary_report_contract.py`, `test_user_sales_performance_report_contract.py`, `test_reports_warehouse_inventory_context_contract.py`.
- Catalog workflows: `test_catalog_activity_filtered_discovery_contract.py`, `test_catalog_activity_hierarchy_selectors.py`, `test_catalog_current_device_location.py`, `test_catalog_discovery_pagination_migration.py`, `test_catalog_unpriced_linking_contract.py`, `test_nearby_catalog_inventory_setup_contract.py`.

Before moving a declaration, search both Kotlin callers and Python source-contract assertions. Update the source owner in those contracts in the same change. The inventory is intentionally not marked complete until every test referencing the four large source files has been reviewed and the baseline commands have actually run.

## Work packages

| ID | Work package | Current state | Acceptance |
|---|---|---|---|
| REF-000 | Inventory repository, Android source, tests, and ownership boundaries | TESTED (documentation inventory) | Existing files and ownership boundaries documented; this is not a runtime test |
| REF-001 | Establish baseline build/test commands and path-sensitive test inventory | PARTIALLY DOCUMENTED | CI commands and high-risk source-contract tests recorded; exhaustive call-site/path inventory and a baseline run on this branch remain required |
| REF-002 | Extract shared network transport and cloud error mapping from cloud repository | PARTIALLY EXTRACTED; TESTS PENDING CI | Shared `SupabaseClient` and `CloudErrorMapper` now own HTTP transport and actionable backend errors; request behavior must be confirmed by focused tests and CI |
| REF-003 | Split cloud repository by domain | PARTIALLY EXTRACTED; CATALOG AND MASTER DATA EXTRACTED IN THIS SLICE | Inventory, sales, purchases, supplier payments, expenses, returns, customer portal, payment requests, and accounting reads and customer receivables, catalog inventory access, and master-data workflows have dedicated repositories; shared session/paging helpers and allowlisted operational records remain in the legacy repository |
| REF-004 | Separate MainActivity composition/navigation from local legacy persistence and screens | PARTIALLY EXTRACTED | Shared and local models moved to dedicated files; next extract persistence/navigation in separate behavior-preserving slices |
| REF-005 | Split report export infrastructure from report UI | EXTRACTED; TESTED pending CI | PDF/XLSX/print functions moved to ReportExport.kt; source-contract tests updated and XLSX unit tests added; run CI before marking tested |
| REF-006 | Split report catalog, cloud data, row builders, filters, and feature screens | IN PROGRESS — catalog, inventory/financial row helpers, formatting, validation, domain mappers, export, cloud data loading, and interactive UI state have dedicated owners; the large report-row orchestration and Compose presentation remain | Each report family has clear owner and calculation tests; screen becomes a coordinator rather than a mixed-responsibility implementation |
| REF-007 | Split catalog search, discovery, directory management, transfers, and invoice linking | IN PROGRESS — category, unit, product-authoring, invoice-review, and transfer presentation sections extracted; browse/discovery remains in the screen | Each catalog workflow has a focused UI owner and the existing request/business rules remain unchanged |
| REF-008 | Split pure report calculations by business domain | PARTIALLY EXTRACTED; TESTS PENDING CI | Payment-request amount parsing/formatting now has its own pure Kotlin owner; formula parity tests and remaining report-domain extraction still required |
| REF-009 | Organize existing feature files and shared UI components | OPEN | No duplicated feature or model owners |
| REF-010 | Organize tests and update code reference | OPEN | Every feature points to focused tests and current source owner |
| REF-011 | Full regression, APK build, and release review | BLOCKED | CI green on final refactor commit; APK built from same SHA |

## Risk-first execution order

1. REF-001: inspect build/test setup and tests that refer to exact source paths or declarations.
2. REF-002 and REF-003: isolate transport first, then move one domain at a time. Keep compatibility wrappers until all callers migrate.
3. REF-004: extract local storage/models and navigation without changing cold-start/auth state transitions.
4. REF-005 and REF-006: separate export, calculations, and presentation without changing totals or report availability.
5. REF-007: split catalog by independent workflows; preserve global catalog vs offering vs local inventory distinctions.
6. REF-008 through REF-010: finish pure-domain and test organization.
7. REF-011: run full validation and build a fresh APK from the exact final commit.

## Work completed on this branch

- Added the architecture overview, module-boundary inventory, and this execution ledger.
- Extracted XLSX generation, native PDF generation, and Android print preview from `ReportsCenterScreen.kt` into `ReportExport.kt`. The functions remain `internal` so existing report UI callers keep their behavior.
- Updated `tests/test_reports_center_source_contract.py` to validate the new export owner.
- Added `ReportExportTest.kt` to validate Arabic RTL XLSX contents, empty-export rejection, XML special-character escaping, and columns beyond `Z`.
- Extracted merchant payment-request amount parsing/formatting from `ReportCalculations.kt` into `CustomerPaymentRequestAmount.kt`; updated the Python source-contract test to enforce the new ownership. Existing Kotlin unit tests remain the behavioral contract.
- Extracted supplier-invoice price-variance models and exact aggregate-rounding calculation into `SupplierInvoicePriceVarianceCalculations.kt`; updated the report source-contract test. The existing focused Kotlin tests continue to target the moved calculation.
- Extracted gross-profit/cost-of-sales report models and calculations, including proportional restocked-cost reversal, into `CostProfitCalculations.kt`; updated both report and item-profitability source-contract tests while keeping shared exact-sum utility in `ReportCalculations.kt`.
- Extracted shared commercial models to `CommercialModels.kt` and local settlement/expense/sell-unit models to `LocalStoreModels.kt`; existing screens and cloud repository continue using the same package-level types.
- Extracted the unchanged SharedPreferences/JSON persistence routines to `LocalStoreRepository.kt`; MainActivity now calls the repository explicitly. Existing preference keys, JSON fields, and organization scoping are preserved. Added `tests/test_local_store_repository_contract.py` to guard the legacy persistence schema and local/cloud loading gates.
- Extracted the local dashboard, sales list, and inventory list into `DashboardScreen.kt`, `LocalSalesScreen.kt`, and `LocalInventoryScreen.kt`; shared UI rows/metrics now live in `StoreUiComponents.kt`. The existing local color palette and legacy YER formatter remain in `StoreUiTheme.kt` and `StoreUiFormatting.kt`. Added `tests/test_local_commercial_screens_contract.py` and updated earlier source-contract tests to follow the new screen owners.
- Extracted the HTTP request executor to `SupabaseClient.kt` and actionable server error mapping to `CloudErrorMapper.kt`; all 64 repository call sites and the profile/onboarding HTTP calls now use the same transport. Added `requestJson` to preserve auth response parsing and its 20-second read timeout. Unified both encrypted-session preference factories in `CommercialCloudSession.kt` without changing the preference file name or encryption scheme.
- Moved customer and merchant payment-request models, RPC calls, and idempotency-key persistence into `CustomerPaymentRequestsRepository.kt`; updated `test_customer_payment_request_android_contract.py`. Shared token/access helpers remain single implementations in the legacy repository and are exposed internally for reuse.
- Moved single/multi-sale checkout, sale idempotency, invoice history projection, and cloud sale summaries into `SalesRepository.kt`; updated sales, invoice-source, unit-directory, and report source-contract tests. Shared paging and master-data access helpers remain in the legacy repository for other domains.
- Moved expense voucher currency/time-zone context, listing, cancellation, idempotency-key persistence, and recording into `ExpenseRepository.kt`; updated expense, voucher atomicity, receivables/expense integrity, and report source-contract tests.
- Moved purchase-order options, order creation/transitions, receipt workflows, supplier invoice models/recording, and their pending idempotency keys into `PurchaseRepository.kt`; updated purchase workflow, supplier invoice, sales screen contract, and report source-contract tests.
- Moved posted supplier-payment movement reads and supplier payment recording into `SupplierPaymentRepository.kt`; updated report and purchase-screen source contracts. The purchase and payment flows retain their existing RPC names and shared transport.
- Moved returnable sale projections, return request/inspection/decision flows, refund state, and restocking into `ReturnsRepository.kt`; updated operational-screen and report contracts. Decimal quantity validation and existing RPCs remain unchanged.
- Moved stock movement history, low-stock projections, costing policy reads/writes, cost-layer summaries, organization-wide location balances, and warehouse mapping readiness projections into `InventoryRepository.kt`; updated stock, costing, warehouse, and report source contracts. Quantities remain `BigDecimal`; no stock mutation or migration was introduced.
- Moved customer portal organization/offering discovery, customer order and booking submission/history, merchant order/booking review, fulfillment, and merchant offering edits into `CustomerPortalRepository.kt`; updated portal, fulfillment, merchant-management, and payment-session source contracts. Existing RPC names, session semantics, and merchant approval boundaries remain unchanged.
- Moved supplier control-account mappings, GL mappings, customer/supplier party-account links, and posted journal-line projections into `AccountingRepository.kt`; updated the report source contract. These remain organization-scoped, posted-only reads and do not create or post ledger entries.
- Moved open customer receivable projections, return/refund-aware due calculations, idempotent customer collection recording, and server-numbered receipt loading into `CustomerAccountsRepository.kt`; updated collection, aging, sales-screen, and financial atomicity contracts. Existing RPC and retry-key behavior remain unchanged.
- Moved cloud inventory loading, sell-unit configuration, catalog-offering lookup, and catalog inventory creation into `CatalogRepository.kt`; moved unit directories, organization master data, and department/warehouse assignments into `MasterDataRepository.kt`. Updated the corresponding sales, unit-directory, and warehouse-assignment source contracts; shared paging/session helpers remain single implementations.
- No database schema, RPC, or production data was changed.

## Current limitations

- The latest PR head has not been confirmed by CI after the last two commits in the prior work.
- No authenticated Android end-to-end commercial workflow has been proven by this source inventory.
- A source move or a passing static contract test does not prove Supabase production readiness.
- This branch starts from the latest reviewed PR head and is intentionally separate; it does not merge PR #714 or change `main`.


## 2026-11-04 — Reports slice continued

- Merged PR #717: report catalog extraction is now part of `refactor/commercial-code-organization`.
- Extracted `ReportLine`, location-inventory report state/mapping, and stock-movement-by-user report state/mapping into `ReportInventoryLines.kt`.
- Updated report source-contract tests to read the new owner file while preserving the original behavioral assertions.
- No database migrations, Supabase RPCs, query scopes, report formulas, or export behavior were changed in this slice.
- Validation is running on the updated branch; do not mark the slice complete or merge PR #715 until all required CI checks pass.


- Continued report decomposition: extracted the shared report row model and inventory report mappers to `ReportInventoryLines.kt`, then extracted cash-flow and tax/discount report row assembly to `ReportFinancialLines.kt`. Report selection, loading, filters, Compose presentation, and export integration remain in `ReportsCenterScreen.kt`.


- Continued catalog decomposition: extracted the activity hierarchy node model and pure sector/parent-chain/business-level selector helpers into `CatalogActivityHierarchy.kt`; updated the selector contract test to follow the new owner. Catalog data loading, RPCs, and Compose interactions remain in `CatalogScreen.kt`.


## 2026-11-04 — Catalog model extraction

- Extracted catalog product/unit/ruling and supporting workflow row models from `CatalogScreen.kt` into `CatalogModels.kt`.
- Kept the existing field names, constructor defaults, package, and call-site type names unchanged; private screen-only model types became package-internal to remain visible across Kotlin files.
- Added `tests/test_catalog_models_ownership.py` to assert one owner for these models and preserve the existing screen signature.
- No RPC, SQL migration, database data, or UI workflow was changed.
- Remaining catalog work is still substantial: separate catalog discovery/data loading, product authoring, market discovery/pricing, invoice-purchase linking, and transfer/review workflows. Do not mark REF-007 complete from this model-only extraction.


- Extracted catalog HTTP request wrappers into `CatalogCloudTransport.kt`, delegating to `SupabaseClient` with the existing 25-second read timeout; removed the duplicate catalog-specific HTTP implementation and encrypted session preference factory from `CatalogScreen.kt`.
- Reused `commercialCloudSessionPreferences` for catalog login/session restore, keeping the same encrypted preference file and keys.
- Added source-contract assertions for the shared transport/session owner. No RPC name, request payload, SQL migration, or database data was changed.


## 2026-11-04 — Report domain mapper extraction

- Extracted supplier invoice price-variance input mapping, sale/return cost-profit input mapping, customer statement input mapping, supplier aging row mapping, report-line formatting, and supplier movement row model into `ReportDomainMappers.kt`.
- Kept all existing mapping fields, filters, and call-site names; the extracted functions are package-internal for use by the report screen.
- Updated the report source-contract test and added a dedicated ownership test.
- No report formulas, SQL, RPCs, data scopes, exports, or persisted data changed. The remaining large report screen still owns loading, filters, state, selected-row projection, and Compose rendering.


## 2026-11-04 — Report formatting extraction

- Moved currency scale resolution, report money formatting, and report date parsing from `ReportsCenterScreen.kt` into `ReportFormatting.kt`.
- Kept helper names and signatures stable so report rows and exports continue using the same formatting contract.
- Added Kotlin unit coverage for currencies with two and zero fraction digits, local whole-unit display, ISO instant/date-only parsing, and invalid currency fallback.
- Restored date/time imports still required by report-period filtering in the Compose screen. No date-window, financial calculation, or export behavior was intentionally changed.


## 2026-11-04 — Report ledger classification extraction

- Moved the report-local account-type normalization, statement classification, and account-net aggregation helpers into `ReportLedgerClassification.kt`.
- Kept the exact classification branches, contra-revenue name checks, COGS aliases, and debit/credit sign rules.
- Added Kotlin tests for classification and balance direction. The report screen now calls the extracted helpers; no ledger query, journal validation, currency grouping, or displayed report label was changed.


## 2026-11-04 — Catalog login panel extraction

- Extracted the login form UI into `CatalogLoginPanel.kt`, with controlled email/password values and a login callback.
- Kept authentication request creation, token validation, refresh-token persistence, session state updates, and feedback in the existing screen workflow.
- Added a source-contract assertion for the new component owner. No authentication endpoint, token key, encryption preference, or login validation rule changed.


## 2026-11-04 — Local sale and product dialog extraction

- Moved `SaleEntryDialog`, `EditProductDialog`, and `AddProductDialog` from `MainActivity.kt` to `LocalProductDialogs.kt`.
- Preserved their public package-level signatures and call sites, including cloud-mode parameters passed into the product dialog.
- Added a source-contract check confirming the new file owns all three composables and MainActivity no longer declares them. No sales arithmetic, unit conversion, local persistence, or cloud API was modified.


## 2026-11-04 — MainActivity entry-point completion

- Extracted the three local sale/product dialogs to `LocalProductDialogs.kt`.
- Extracted More, expenses, expense entry, local reports, legacy cloud reports, debt, and settlement composables to `LocalOperationalScreens.kt`.
- Reduced `MainActivity.kt` from 613 lines after earlier extractions to a 32-line Android entry point plus the shared cloud executor/handler.
- Added source-contract tests that enforce the thin activity boundary and ensure every moved composable has one owner. Existing function names and call sites are preserved; no local/cloud data workflow was intentionally changed.


### Completed follow-up — catalog market parsing

- Extracted market-context, nearby-catalog, and aggregated price-suggestion response mapping into `CatalogMarketData.kt`.
- Added Kotlin unit coverage for missing branch coordinates, warehouse association, nearby item mapping, and price-hint formatting.
- Kept location permission handling, pagination, RPC calls, selection state, and user-facing feedback in `CatalogScreen.kt`; no RPC, database, or business-rule contract was changed.


## 2026-11-04 — Catalog discovery product mapping extraction

- Moved the catalog discovery-page JSON-to-`CatalogProduct` mapping, including localized display fallbacks, unit options, raw metadata, expiry/tracking flags, and rulings, into `CatalogProductMapper.kt`.
- Kept the RPC request, page size, offset progression, de-duplication, loading state, and user feedback in `CatalogScreen.kt`. Pagination still advances by the returned row count and still treats a full 100-row page as potentially having more results.
- Added JVM tests for nested units/rulings, localized display overrides with original raw values retained, and empty pages; added a Python ownership guard.
- No RPC name or payload, database schema, item model, or catalog business rule was changed.


## 2026-11-04 — Catalog directory mapping extraction

- Extracted the combined catalog master-data response parser into `CatalogDirectoryMapper.kt`: local unit codes, activity-directory nodes, translated categories/descriptions, and translated global units.
- Kept the seven existing catalog directory GET/RPC calls, locale selection, initial activity/sector inference, screen state updates, user feedback, and subsequent market-context loading in `CatalogScreen.kt`.
- Added `CatalogDirectoryMapperTest.kt` for translations, null/default fields, activity-node filtering, local-unit normalization, empty optional collections, and malformed required data.
- The response keys and domain model constructors remain unchanged; no RPC, SQL, unit conversion, or catalog-selection behavior was intentionally changed.


## 2026-11-04 — Catalog workflow response mapping extraction

- Extracted invoice-purchase request and pending catalog-transfer JSON mapping into `CatalogWorkflowMappers.kt`.
- Kept both existing REST request URLs, organization filters, sorting, limits, action callbacks, feedback, and refresh behavior in `CatalogScreen.kt`.
- Added `CatalogWorkflowMappersTest.kt` for request field mapping, nullable purchase-order references, transfer fields, and empty responses.
- Added a source-ownership guard. No RPC/REST contract, status transition, transfer action, or invoice workflow behavior was intentionally changed.


## 2026-11-04 — Catalog invoice-mapping response extraction

- Extracted the prepared invoice-item mapping payload parser into `CatalogInvoiceMappingMapper.kt`, covering the receive location, warehouse options, unmapped purchase lines, and eligible catalog offerings.
- Kept purchase-order validation, supplier-invoice lock checks, all REST reads and filtering, active offering/product eligibility, candidate construction, mapping selection defaults, and user feedback in `CatalogScreen.kt`.
- Added `CatalogInvoiceMappingMapperTest.kt` for mapped fields and empty optional collections, plus a source-ownership guard.
- No endpoint, organization/location scope, eligibility condition, invoice matching action, or inventory behavior was intentionally changed.


## 2026-11-04 — Reports ledger-validation extraction

- Extracted the financial-statement source validation from `ReportsCenterScreen.kt` into `ReportLedgerValidation.kt`.
- Kept cloud data loading, Compose state, filter state, report selection, calculations, and rendering in their existing owners.
- Added `ReportLedgerValidationTest.kt` covering fail-closed validation and the valid balanced-ledger case.
- The extraction preserves validation ordering and all existing checks; it does not alter SQL/RPC contracts or financial formulas.


## 2026-11-04 — Catalog screen decomposition slice 1

- Extracted the category editor, unit editor, and catalog-transfer request list from `CatalogScreen.kt` into `CatalogScreenSections.kt`.
- The screen retains workflow orchestration and passes existing Compose state holders and action callbacks to the presentation sections.
- The extracted components do not create network clients, issue RPCs, or change approval/transfer behavior.
- `CatalogScreen.kt` remains responsible for browse/discovery, product authoring, and invoice-to-purchase workflows; REF-007 remains in progress until those sections are separated and focused tests/builds pass.

- Follow-up extraction: `CatalogProductFormState.kt` now owns the product-authoring form state, and `CatalogScreenSections.kt` owns its presentation. The screen retains the existing product creation action and RPC orchestration.

- Follow-up extraction: `CatalogInvoiceTransferState.kt` owns invoice-review and matching UI state; `CatalogScreenSections.kt` owns the invoice-to-purchase review presentation. Request creation, approvals, matching actions, and all RPC orchestration remain in `CatalogScreen.kt`.


## 2026-11-04 — Catalog market UI state ownership

- Moved branch/warehouse selection, branch coordinates, nearby-market results and pagination state, location-session values, and aggregated price-hint state into `CatalogMarketUiState.kt`.
- Kept permission prompts, foreground location acquisition, UI rendering, RPC/REST orchestration, and catalog configuration actions in `CatalogScreen.kt`; this is a state-ownership step, not completion of the browse/discovery presentation extraction.
- Added a source-ownership guard for the new market state owner. No network contract, database schema, or production data changed.


## 2026-11-04 — Catalog nearby-market presentation extraction

- Extracted the branch/warehouse selector, coordinate editor, nearby-market controls, and nearby popularity list into `CatalogMarketDiscoverySection.kt`.
- The section receives UI state and explicit callbacks; it does not issue HTTP/RPC requests or handle Android location permissions. The owning screen retains coordinate persistence, branch/warehouse loading, nearby pagination orchestration, price-suggestion loading, catalog setup, and selection-to-organization linking.
- Updated coordinate, foreground-location, nearby-discovery, and inventory-setup source contracts to follow the new presentation/state owners.
- Catalog browsing is now split across dedicated market presentation and state owners; the main catalog product list and activity filters remain in `CatalogScreen.kt` and are the next presentation extraction target.


## 2026-11-04 — Catalog product result presentation extraction

- Extracted the global catalog result card into `CatalogProductResultCard.kt`, including metadata, package images, rulings, source links, selection control, and inventory-link action presentation.
- The card uses explicit callbacks for selection, feedback, source navigation, ruling review, inventory creation, and product editing. It does not own network requests or edit workflow state.
- Kept the catalog result query, activity filters, pagination, bulk unpriced-link workflow, and product edit dialog orchestration in `CatalogScreen.kt`.
- Added a source-ownership guard and updated the universal catalog contract to read the extracted presentation owners.


## 2026-11-04 — Catalog browse presentation extraction

- Extracted catalog browse filters and the paginated global result list into `CatalogBrowseSection.kt`.
- Kept query/filter state, selection updates, RPC-triggering callbacks, pagination, and inventory-linking workflows in `CatalogScreen.kt`; the new component is presentation-only and callback-driven.
- Updated source-contract tests to follow the extracted market state, product card, and browse presentation owners.
- No RPC payload, database migration, or production data changed.


## 2026-11-04 — Reports cloud data extraction

- Added `ReportsCloudData.kt` as the owner of cloud report projections and refresh lifecycle.
- Replaced the screen's individual mutable cloud-result fields and inline IO/handler refresh block with one immutable `ReportsCloudData` snapshot, while retaining local aliases so the existing report calculations and presentation remain unchanged.
- Kept each source independently guarded, cleared failed projections, retained source-specific error messages, and preserved sequential loading and existing repository loader calls.
- Updated the source-contract aggregation and module/code-reference ownership documentation. No report formula, query scope, RPC, SQL migration, or production data was changed.
- REF-006 remains in progress: the large report-row selection/orchestration block and Compose presentation still require separate ownership before this refactor can be considered complete.


- Added `ReportsCenterUiState.kt` as the single owner for report filter/selection/export-feedback state; `ReportsCenterScreen.kt` now consumes the state holders through delegated properties. The report-row orchestration and Compose presentation remain to be decomposed.


- Extracted the report period preset/manual date input UI to `ReportsPeriodControls.kt`; the component emits range changes through callbacks while date validation and report filtering remain with the screen. Added a source-ownership test.


- Extracted Android document-picker callbacks and CSV/XLSX/PDF stream/share lifecycle into `ReportsExportLaunchers.kt`; native document rendering remains in `ReportExport.kt`, and report-row selection remains in the screen. Added an ownership test and moved source-contract assertions to the new owner.


- Extracted report category chips, catalog cards, source-readiness labels, and report-title selection mapping to `ReportsCatalogSection.kt` using a `LazyListScope` extension. The screen now passes state and callbacks; catalog definitions and calculations remain in their existing owners.


- Extracted the inventory report branches from the large selected-row switch into `ReportInventoryRows.kt` and added a source-ownership test. The screen delegates inventory report selections to this builder; remaining sales, purchase, party/ledger, expense, and financial statement row assembly still needs decomposition.


- Extracted the income statement/balance sheet, trial balance, general-ledger movement/account statement, posted journal detail, and journal register row branches into `ReportLedgerRows.kt`. Added a source-ownership test; remaining sales, purchase, party statement, customer/supplier operational, and expense row assembly still needs decomposition.


- Extracted full customer/supplier control-account statements and the operational customer statement into `ReportPartyStatementRows.kt`; added a source-ownership test. Sales, purchase, supplier operational movement, and expense row families remain in the main dispatch and are the next extraction targets.


- Extracted sales summaries, invoice details/lines, customer/item/payment breakdowns, tax/discount rows, average invoice values, and period comparisons into `ReportSalesRows.kt`; added a source-ownership test. User-performance/daily-trend/aging/collection cases and purchase/expense/return families remain for subsequent extraction.


- Extracted supplier invoice price variance, recorded invoices/obligations, obligation totals, and supplier movement report rows into `ReportSupplierRows.kt`; added a source-ownership test. Remaining dispatch branches include performance/trend/aging/collections, returns, payment records, detailed tax, cost/profit, and expense rows.


- Extracted payment, settlement, return-status, and expense report rows into `ReportOperationalRows.kt`; added a source-ownership test. Remaining main-switch branches include performance/trend/aging/collections, detailed tax, profitability, supplier purchase summaries/aging, and a few orchestration-only cases.


- Extracted row mapping for user sales performance, daily sales trend, customer receivable aging/collections, supplier purchase summaries, and supplier aging into `ReportDerivedRows.kt`; added a source-ownership test. Remaining report switch logic is now mostly detailed tax, cost/profit, cash-flow summary, and orchestration.


- Extracted detailed tax/discount row mapping into `ReportTaxRows.kt`; added an ownership test. Tax source validation and the existing tax/discount summary calculator remain in their existing owners.
