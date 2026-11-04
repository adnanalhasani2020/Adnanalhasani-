from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportSupplierRows.kt"


def test_supplier_report_row_assembly_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildSupplierReportRows(" in data
    assert "buildSupplierReportRows(" not in screen
    for marker in (
        '"فروقات أسعار فواتير الموردين" ->',
        '"فواتير الشراء والمستحقات المسجلة" ->',
        '"مستحقات فواتير الموردين" ->',
        '"كشف حركات الموردين" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "calculateSupplierInvoicePriceVariance" in rows
    assert "calculateSupplierObligationTotals" in rows
    assert "cloudSupplierPayments" in rows
    assert "supplierFilter" in rows
    assert "Math.addExact" in rows
