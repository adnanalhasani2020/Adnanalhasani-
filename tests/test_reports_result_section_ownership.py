from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
SECTION = ANDROID / "ReportsResultSection.kt"


def test_report_result_cards_and_export_actions_have_a_dedicated_ui_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    section = SECTION.read_text(encoding="utf-8")

    assert "reportsResultSection(" in screen
    assert "launchers = exportLaunchers" in screen
    assert "onExportStateChanged = { title, rows -> exportTitle = title; exportRows = rows }" in screen
    assert "buildReportExportRows(" in section
    assert "LazyListScope.reportsResultSection(" in section
    assert "reportLineAmount(row, cloudMode)" in section
    assert "launchers.csv.launch" in section
    assert "launchers.xlsx.launch" in section
    assert "launchers.pdf.launch" in section
    assert "printReport(context, selectedReport, rows)" in section
    assert "items(selectedRows)" in section
