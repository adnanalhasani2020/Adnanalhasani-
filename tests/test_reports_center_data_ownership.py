from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA = ANDROID / "ReportsCenterData.kt"


def test_reports_center_screen_is_a_thin_coordinator_over_report_data_state():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA.read_text(encoding="utf-8")

    assert "rememberReportsCenterData(" in screen
    assert "val selectedRows = reportData.selectedRows" in screen
    assert "val sourceErrorForReport = reportData.sourceErrorForReport" in screen
    assert "val currencyTotal = reportData.currencyTotal" in screen
    for marker in (
        "reportLedgerStatementValidationError(",
        "reportSupplierInvoiceVarianceError(",
        "reportSupplierInvoiceDetailValidationError(",
        "reportSupplierPurchaseSummaryValidationError(",
        "reportSupplierObligationsValidationError(",
        "reportCostProfitValidationError(",
        "reportTaxDetailValidationError(",
        "reportCustomerStatementValidationError(",
        "reportSupplierStatementValidationError(",
        "calculateItemProfitability(",
        "calculateCustomerCollectionsByCustomer(",
        "calculateUserSalesPerformance(",
        "calculateDailySalesTrend(",
        "buildReportsCatalog(cloudMode)",
        "selectedRowsUnvalidated = buildInventoryReportRows(",
    ):
        assert marker in data
        assert marker not in screen
    assert "ReportsCenterData(" in data
    assert "sourceErrorForReport: (String) -> String" in data
