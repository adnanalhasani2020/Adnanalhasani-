from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
CLOUD_DATA = ANDROID / "ReportsCloudData.kt"


def test_reports_cloud_projection_has_a_single_non_ui_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    cloud_data = CLOUD_DATA.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")

    assert "rememberReportsCloudData(context, cloudMode, cloudReload)" in screen
    assert "val cloudSalesDocuments = cloudData.salesDocuments" in data
    assert "val cloudLedgerLines = cloudData.ledgerLines" in data
    assert "val cloudLoading = cloudData.loading" in data

    for loader in (
        "loadCommercialCloudSaleDocuments(context)",
        "loadCommercialCloudReceivables(context)",
        "loadCommercialCloudPurchaseDocuments(context)",
        "loadCommercialCloudSupplierPaymentMovements(context)",
        "loadCommercialCloudStockMovements(context)",
        "loadCommercialCloudStockAlerts(context)",
        "loadCommercialCloudLocationInventoryBalances(context)",
        "loadCommercialCloudWarehouseInventoryContext(context)",
        "loadCommercialCloudSaleReturns(context)",
        'loadCommercialCloudRecords(context, "payments")',
        'loadCommercialCloudRecords(context, "journals")',
        "loadCommercialCloudPostedJournalLines(context)",
        "loadCommercialCloudSupplierControlAccounts(context)",
        "loadCommercialCloudGlAccountMappings(context)",
        "loadCommercialCloudCustomerPartyAccounts(context)",
        "loadCommercialCloudSupplierPartyAccounts(context)",
        "loadCommercialCloudExpenses(context)",
    ):
        assert loader in cloud_data
        assert loader not in screen


def test_failed_cloud_projections_are_cleared_and_keep_source_errors():
    cloud_data = CLOUD_DATA.read_text(encoding="utf-8")

    assert "runCatching" in cloud_data
    assert ".getOrDefault(emptyList())" in cloud_data
    assert "exceptionOrNull()?.message.orEmpty()" in cloud_data
    assert "withContext(Dispatchers.IO)" in cloud_data
    assert "data = loaded" in cloud_data
    assert "loading = false" in cloud_data
