from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_user_sales_performance_uses_persisted_sale_actor_and_separate_currencies():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    assert "customer_id,user_id,receipt_number" in repository
    assert 'userId = row.optString("user_id")' in repository
    assert 'ReportCatalogItem("أداء المبيعات حسب المستخدم"' in screen
    assert 'UserSalesPerformanceInvoiceInput(' in screen
    assert "internal fun calculateUserSalesPerformance(" in calculations
    assert "sumReportAmountsExactly(rows.map { it.totalMinor })" in calculations
    assert 'document.saleStatus.equals("completed", true)' in screen
    assert "Branch and warehouse performance remain separate unfinished work" in contract


def test_user_sales_performance_fails_closed_for_invalid_money_and_duplicate_invoice_ids():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert "invoice.invoiceId.isNotBlank() && seen.add(invoice.invoiceId)" in calculations
    assert 'invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.totalMinor >= 0L' in calculations
