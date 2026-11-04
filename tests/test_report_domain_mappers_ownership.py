from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
MAPPERS = ANDROID / "ReportDomainMappers.kt"
RESULTS = ANDROID / "ReportsResultSection.kt"
DERIVED_ROWS = ANDROID / "ReportDerivedRows.kt"


def test_pure_report_mappers_have_a_single_non_ui_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    mappers = MAPPERS.read_text(encoding="utf-8")

    for marker in (
        "toPriceVarianceInput",
        "toCostProfitInput",
        "toCustomerStatementInputs",
        "reportLineAmount",
        "toReportLines",
        "ReportSupplierMovement",
    ):
        assert marker in mappers

    for declaration in (
        "fun CommercialCloudSupplierInvoiceSummary.toPriceVarianceInput",
        "fun CommercialCloudSaleDocument.toCostProfitInput",
        "fun CommercialCloudSaleReturn.toCostProfitInput",
        "fun List<CommercialCloudSaleDocument>.toCustomerStatementInputs",
        "fun reportLineAmount(",
        "fun SupplierDueDateAgingReportState.toReportLines",
        "data class ReportSupplierMovement",
    ):
        assert declaration not in screen


def test_report_screen_consumes_the_extracted_mapper_contracts():
    data = DATA_STATE.read_text(encoding="utf-8")
    results = RESULTS.read_text(encoding="utf-8")
    derived_rows = DERIVED_ROWS.read_text(encoding="utf-8")
    assert "toPriceVarianceInput()" in data
    assert "toCostProfitInput()" in data
    assert "toCustomerStatementInputs()" in data
    assert "reportLineAmount(row, cloudMode)" in results
    assert "report.toReportLines(asOfDate)" in derived_rows
