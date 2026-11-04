from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
EXPORT = ANDROID / "ReportsExportLaunchers.kt"


def test_android_document_export_lifecycle_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    export = EXPORT.read_text(encoding="utf-8")

    assert "rememberReportsExportLaunchers(" in screen
    assert "val csvLauncher = exportLaunchers.csv" in screen
    assert "val xlsxLauncher = exportLaunchers.xlsx" in screen
    assert "val pdfLauncher = exportLaunchers.pdf" in screen
    assert "rememberLauncherForActivityResult" in export
    assert "writeReportXlsx(it, exportRows)" in export
    assert "writeReportPdf(it, exportRows)" in export
    assert "Intent.ACTION_SEND" in export
    assert 'writer.write("\\uFEFF")' in export
    assert "require(!dateError)" in export
    assert "selectedReportSourceError.isBlank()" in export
