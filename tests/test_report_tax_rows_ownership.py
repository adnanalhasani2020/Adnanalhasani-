from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportTaxRows.kt"


def test_detailed_tax_rows_have_a_dedicated_mapper():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildDetailedTaxReportRows(" in data
    assert "buildDetailedTaxReportRows(" not in screen
    assert '"الضرائب والخصومات التفصيلية" -> buildDetailedTaxReportRows(' in data
    assert "taxDetailError.isNotBlank()" in rows
    assert "commercial_sales.discount_minor" in rows
    assert "commercial_sales.tax_minor" in rows
    assert "commercial_supplier_invoices.tax_minor" in rows
    assert "saleReturn.approvedRefundMinor!!" in rows
    assert "لا تفصل حصة الضريبة" in rows
