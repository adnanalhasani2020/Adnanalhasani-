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
- Reports/calculations: `test_reports_center_source_contract.py`, `test_item_profitability_report_contract.py`, `test_customer_collections_report_contract.py`, `test_customer_receivable_aging_report_contract.py`, `test_supplier_liability_aging_report_contract.py`, `test_tax_discounts_summary_report_contract.py`, `test_user_sales_performance_report_contract.py`, `test_reports_warehouse_inventory_context_contract.py`.
- Catalog workflows: `test_catalog_activity_filtered_discovery_contract.py`, `test_catalog_activity_hierarchy_selectors.py`, `test_catalog_current_device_location.py`, `test_catalog_discovery_pagination_migration.py`, `test_catalog_unpriced_linking_contract.py`, `test_nearby_catalog_inventory_setup_contract.py`.

Before moving a declaration, search both Kotlin callers and Python source-contract assertions. Update the source owner in those contracts in the same change. The inventory is intentionally not marked complete until every test referencing the four large source files has been reviewed and the baseline commands have actually run.

## Work packages

| ID | Work package | Current state | Acceptance |
|---|---|---|---|
| REF-000 | Inventory repository, Android source, tests, and ownership boundaries | TESTED (documentation inventory) | Existing files and ownership boundaries documented; this is not a runtime test |
| REF-001 | Establish baseline build/test commands and path-sensitive test inventory | PARTIALLY DOCUMENTED | CI commands and high-risk source-contract tests recorded; exhaustive call-site/path inventory and a baseline run on this branch remain required |
| REF-002 | Extract shared network transport and cloud error mapping from cloud repository | PARTIALLY EXTRACTED; TESTS PENDING CI | Shared `SupabaseClient` and `CloudErrorMapper` now own HTTP transport and actionable backend errors; request behavior must be confirmed by focused tests and CI |
| REF-003 | Split cloud repository by domain | PARTIALLY EXTRACTED; TESTS PENDING CI | Customer payment-request workflows now have a dedicated repository; remaining inventory, purchase, returns, supplier-payment, portal, and accounting domains still reside in the legacy repository |
| REF-004 | Separate MainActivity composition/navigation from local legacy persistence and screens | PARTIALLY EXTRACTED | Shared and local models moved to dedicated files; next extract persistence/navigation in separate behavior-preserving slices |
| REF-005 | Split report export infrastructure from report UI | EXTRACTED; TESTED pending CI | PDF/XLSX/print functions moved to ReportExport.kt; source-contract tests updated and XLSX unit tests added; run CI before marking tested |
| REF-006 | Split report catalog, data builders, filters, and feature screens | OPEN | Each report family has clear owner and calculation tests |
| REF-007 | Split catalog search, discovery, directory management, transfers, and invoice linking | OPEN | Catalog workflows remain intact |
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
- Moved expense voucher currency/time-zone context, listing, cancellation, idempotency-key persistence, and recording into `ExpenseRepository.kt`; updated expense, voucher atomicity, receivables/expense integrity, and report source-contract tests. Supplier-payment recording remains in the legacy repository pending its own domain extraction.
- No database schema, RPC, or production data was changed.

## Current limitations

- The latest PR head has not been confirmed by CI after the last two commits in the prior work.
- No authenticated Android end-to-end commercial workflow has been proven by this source inventory.
- A source move or a passing static contract test does not prove Supabase production readiness.
- This branch starts from the latest reviewed PR head and is intentionally separate; it does not merge PR #714 or change `main`.
