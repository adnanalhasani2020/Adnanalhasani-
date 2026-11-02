from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_supplier_liability_age_report_uses_recorded_invoice_date_and_open_obligation():
    screen = SCREEN.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("عمر مستحقات الموردين حسب تاريخ الفاتورة"' in screen
    assert 'invoiceDate = invoice.recordedAt' in screen
    assert 'amountDueMinor = obligation.amountDueMinor' in screen
    assert 'amountPaidMinor = obligation.amountPaidMinor' in screen
    assert 'calculateSupplierLiabilityAging(supplierLiabilityAgingInputs, endText)' in screen
    assert 'dateError -> "حدد تاريخ نهاية صالحًا لحساب أعمار مستحقات الموردين."' in screen
    assert 'ChronoUnit.DAYS.between(date, asOf)' in calculations
    assert 'أكثر من 90 يومًا' in calculations
    assert 'not days overdue' in contract.lower()
    assert 'not contractual due-date aging' in contract.lower()


def test_supplier_liability_age_report_keeps_original_due_date_report_blocked():
    screen = SCREEN.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("أعمار مستحقات الموردين"' in screen
    assert 'false, "جدول التزامات الموردين لا يحتوي تاريخ استحقاق' in screen
