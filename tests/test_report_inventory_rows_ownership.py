from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportInventoryRows.kt"


def test_inventory_report_row_assembly_has_a_single_domain_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildInventoryReportRows(" in data
    assert "buildInventoryReportRows(" not in screen
    assert "selectedRowsUnvalidated = buildInventoryReportRows(" in data
    for report in (
        "التقييم المالي الحالي للمخزون",
        "حركة المخزون حسب المستخدم",
        "حركة المخزون الفعلية",
        "رصيد المخزون حسب الموقع",
        "حالة ربط أرصدة المخزون بالمستودعات",
        "رصيد المخزون الحالي",
        "تنبيهات المخزون المنخفض",
    ):
        assert f'"{report}" ->' in rows
        assert f'"{report}" ->' not in screen
    assert "sourceErrorForReport(selectedReport)" in rows
    assert "reportDateMillis(movement.createdAt)" in rows
