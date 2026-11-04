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
        assert cloud_data.count(loader) == 1
        assert loader not in screen


def test_failed_cloud_projections_are_cleared_and_keep_source_errors():
    cloud_data = CLOUD_DATA.read_text(encoding="utf-8")

    assert "loadReportProjection" in cloud_data
    assert "CancellationException" in cloud_data
    assert "currentCoroutineContext().ensureActive()" in cloud_data
    assert "catch (cancelled: CancellationException)" in cloud_data
    assert ".getOrDefault(emptyList())" in cloud_data
    assert "exceptionOrNull()?.message.orEmpty()" in cloud_data
    assert "withContext(Dispatchers.IO)" in cloud_data
    assert "data = loaded" in cloud_data
    assert "loading = false" in cloud_data


def test_every_cloud_projection_failure_triggers_the_shared_retry_signal():
    cloud_data = CLOUD_DATA.read_text(encoding="utf-8")
    errors_block = cloud_data.split("val errors = listOf(", 1)[1].split(").filterNotNull()", 1)[0]

    for source in (
        "salesDocuments",
        "receivables",
        "purchases",
        "supplierPayments",
        "stockMovements",
        "stockAlerts",
        "locationInventory",
        "warehouseInventoryContext",
        "returns",
        "payments",
        "journals",
        "ledgerLines",
        "supplierControlAccounts",
        "glMappings",
        "customerPartyAccounts",
        "supplierPartyAccounts",
        "expenses",
    ):
        assert f"{source}.exceptionOrNull()" in errors_block

    assert "loadError = errors.firstOrNull()?.message" in cloud_data


def test_canceled_refresh_does_not_start_later_source_requests():
    cloud_data = CLOUD_DATA.read_text(encoding="utf-8")

    assert "private suspend inline fun <T> loadReportProjection" in cloud_data
    assert "currentCoroutineContext().ensureActive()" in cloud_data
    assert "throw cancelled" in cloud_data
    assert cloud_data.count("loadReportProjection { loadCommercialCloud") == 17
    assert cloud_data.index("val expenses = loadReportProjection") < cloud_data.index(
        "currentCoroutineContext().ensureActive()", cloud_data.index("val expenses = loadReportProjection")
    )
