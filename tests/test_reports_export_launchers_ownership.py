from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
EXPORT = ANDROID / "ReportsExportLaunchers.kt"
RESULTS = ANDROID / "ReportsResultSection.kt"


def test_android_document_export_lifecycle_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    export = EXPORT.read_text(encoding="utf-8")
    results = RESULTS.read_text(encoding="utf-8")

    assert "rememberReportsExportLaunchers(" in screen
    assert "val csv = rememberLauncherForActivityResult" in export
    assert "val xlsx = rememberLauncherForActivityResult" in export
    assert "val pdf = rememberLauncherForActivityResult" in export
    assert "launchers.csv.launch(" in results
    assert "launchers.xlsx.launch(" in results
    assert "launchers.pdf.launch(" in results
    assert "rememberLauncherForActivityResult" in export
    assert "writeReportXlsx(it, exportRows)" in export
    assert "writeReportPdf(it, exportRows)" in export
    assert "Intent.ACTION_SEND" in export
    assert 'writer.write("\\uFEFF")' in export
    assert "require(!dateError)" in export
    assert "selectedReportSourceError.isBlank()" in export
