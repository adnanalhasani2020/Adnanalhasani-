from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCatalog.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
AGING = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportAgingCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_supplier_liability_age_report_uses_recorded_invoice_date_and_open_obligation():
    screen = SCREEN.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    aging = AGING.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("عمر مستحقات الموردين حسب تاريخ الفاتورة"' in screen
    assert 'invoiceDate = invoice.recordedAt' in aging
    assert 'amountDueMinor = obligation.amountDueMinor' in aging
    assert 'amountPaidMinor = obligation.amountPaidMinor' in aging
    assert 'buildSupplierLiabilityAgingReport(' in screen
    assert 'calculateSupplierLiabilityAging(inputs, asOfDate)' in aging
    assert 'ChronoUnit.DAYS.between(date, asOf)' in calculations
    assert 'أكثر من 90 يومًا' in calculations
    assert 'not days overdue' in contract.lower()
    assert 'not contractual due-date aging' in contract.lower()


def test_contractual_supplier_due_date_aging_is_enabled_from_saved_invoice_dates():
    screen = SCREEN.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("أعمار مستحقات الموردين", "الموردون والمستحقات", "commercial_supplier_invoices.invoice_date + due_date + commercial_supplier_obligations", cloudMode' in screen
    assert '"أعمار مستحقات الموردين" -> supplierDueDateAgingError' in screen
    assert '"أعمار مستحقات الموردين" -> if (supplierDueDateAgingError.isNotBlank()) emptyList()' in screen
