from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
ROWS = ANDROID / "ReportSalesRows.kt"


def test_sales_report_row_assembly_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildSalesReportRows(" in screen
    for marker in (
        '"ملخص المبيعات حسب الفترة" ->',
        '"تفاصيل فواتير المبيعات" ->',
        '"تفاصيل بنود فواتير المبيعات" ->',
        '"المبيعات حسب الصنف" ->',
        '"الخصومات على فواتير المبيعات" ->',
        '"الضرائب المسجلة على المبيعات" ->',
        '"المبيعات حسب العميل" ->',
        '"المبيعات حسب حالة السداد" ->',
        '"المبيعات النقدية والآجلة" ->',
        '"متوسط قيمة الفاتورة" ->',
        '"مقارنة فترتين", "مقارنة الفترات" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "inPeriodCloudSalesDocuments" in rows
    assert "inPeriodSales" in rows
    assert "previousCloudRows" in rows
