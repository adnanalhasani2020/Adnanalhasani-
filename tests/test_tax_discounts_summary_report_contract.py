from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_tax_discount_summary_is_registered_and_uses_existing_validated_sources():
    screen = SCREEN.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    assert 'ReportCatalogItem("ملخص الضرائب والخصومات"' in screen
    assert '"ملخص الضرائب والخصومات" -> taxDiscountSummaryError' in screen
    assert '"ملخص الضرائب والخصومات" -> if (taxDiscountSummaryError.isNotBlank()) emptyList()' in screen
    assert 'TaxDiscountSummaryInput(doc.id, doc.currencyCode, "sales_discount", doc.discountMinor)' in screen
    assert 'TaxDiscountSummaryInput(doc.id, doc.currencyCode, "sales_tax", doc.taxMinor)' in screen
    assert '"purchase_tax", invoice.taxMinor' in screen
    assert '"sales_return", refund' in screen
    assert "does not calculate net tax payable" in contract
    assert "does not infer a refundable tax portion" in contract


def test_tax_discount_calculation_keeps_currency_and_categories_separate():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert "internal fun calculateTaxDiscountSummaryTotals(" in calculations
    assert 'records.groupBy { it.currencyCode }' in calculations
    assert 'amount("sales_discount")' in calculations
    assert 'amount("sales_tax")' in calculations
    assert 'amount("purchase_tax")' in calculations
    assert 'amount("sales_return")' in calculations
    assert "sumReportAmountsExactly" in calculations
    assert 'row.currencyCode.matches(Regex("^[A-Z]{3}$")) && row.amountMinor >= 0L' in calculations
    assert 'seen.add(row.kind + ":" + row.documentId)' in calculations


def test_summary_does_not_label_returns_as_tax_refunds_or_claim_official_tax_liability():
    screen = SCREEN.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert "لا تمثل هذه القيمة ضريبة مستردة" in screen
    assert "لا يحسب صافي التزام ضريبي ولا يمثل إقرارًا ضريبيًا" in screen
    assert "tax recovered" in contract
