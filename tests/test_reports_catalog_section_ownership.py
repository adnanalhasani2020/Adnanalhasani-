from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
SECTION = ANDROID / "ReportsCatalogSection.kt"


def test_report_catalog_cards_are_a_presentation_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    section = SECTION.read_text(encoding="utf-8")

    assert "reportsCatalogSection(" in screen
    assert "onReportSelected = { selectedReport = it }" in screen
    assert "LazyListScope.reportsCatalogSection" in section
    assert "sourceErrorForReport(report.title)" in section
    assert "report.supported && !dateError" in section
    assert "supplierPaymentsLoadError.isNotBlank()" in section
    assert "stockMovementsLoadError.isNotBlank()" in section
    assert "stockAlertsLoadError.isNotBlank()" in section
    assert "private fun selectableReportTitle" in section
    assert "لوحة المؤشرات" in section
    assert "ملخص المبيعات حسب الفترة" in section
    assert "loadCommercialCloud" not in section
