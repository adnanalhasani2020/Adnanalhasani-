from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportDerivedRows.kt"


def test_derived_performance_aging_and_collection_rows_have_a_mapper_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    for function in (
        "buildUserSalesPerformanceRows(",
        "buildDailySalesTrendRows(",
        "buildCustomerReceivableAgingRows(",
        "buildCustomerCollectionsRows(",
        "buildSupplierPurchaseSummaryRows(",
        "buildSupplierLiabilityAgingRows(",
        "buildSupplierDueDateAgingRows(",
    ):
        assert function in rows
        assert function in data
        assert function not in screen
    assert "calculation.getOrThrow()" in rows
    assert "error.isNotBlank()" in rows
    assert "report.toReportLines(asOfDate)" in rows
    assert "متوسط الفاتورة" in rows
    assert "العمر من تاريخ الفاتورة" in rows
