from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
DATA_STATE = ANDROID / "ReportsCenterData.kt"


def test_every_cloud_projection_has_a_live_report_consumer():
    data = DATA_STATE.read_text(encoding="utf-8")
    projection_aliases = (
        "cloudSalesDocuments",
        "cloudReceivables",
        "cloudPurchases",
        "cloudSupplierPayments",
        "cloudStockMovements",
        "cloudStockAlerts",
        "cloudLocationInventoryBalances",
        "cloudWarehouseInventoryContext",
        "cloudReturns",
        "cloudPayments",
        "cloudJournals",
        "cloudLedgerLines",
        "cloudSupplierControlAccounts",
        "cloudGlMappings",
        "cloudCustomerPartyAccounts",
        "cloudSupplierPartyAccounts",
        "cloudExpenses",
    )

    for alias in projection_aliases:
        # One occurrence declares the alias from ReportsCloudData; at least
        # one further occurrence must consume it in validation or row building.
        assert data.count(alias) >= 2, f"Cloud projection has no downstream consumer: {alias}"


def test_specialized_cloud_errors_remain_connected_to_report_readiness():
    data = DATA_STATE.read_text(encoding="utf-8")
    source_errors = (
        "receivablesLoadError",
        "purchasesLoadError",
        "supplierPaymentsLoadError",
        "stockMovementsLoadError",
        "stockAlertsLoadError",
        "locationInventoryLoadError",
        "warehouseInventoryContextLoadError",
        "returnsLoadError",
        "paymentsLoadError",
        "journalsLoadError",
        "ledgerLinesLoadError",
        "supplierControlAccountsLoadError",
        "glMappingsLoadError",
        "customerPartyAccountsLoadError",
        "supplierPartyAccountsLoadError",
        "expensesLoadError",
    )

    for source_error in source_errors:
        assert data.count(source_error) >= 2, f"Source error is not routed to a consumer: {source_error}"
