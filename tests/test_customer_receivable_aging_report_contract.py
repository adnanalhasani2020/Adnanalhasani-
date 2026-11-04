from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
AGING = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerReceivableAgingReport.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerAccountsRepository.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_customer_receivable_age_report_uses_current_return_adjusted_balances():
    screen = SCREEN.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    aging = AGING.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("عمر أرصدة العملاء حسب تاريخ الفاتورة"' in screen
    assert 'loadCommercialCloudReceivables(context)' in screen
    assert 'balanceMinor = receivable.dueMinor' in aging
    assert 'buildCustomerReceivableAgingReport(' in screen
    assert 'calculateCustomerReceivableAging(inputs, asOfDate)' in aging
    assert 'ChronoUnit.DAYS.between(date, asOf)' in calculations
    assert 'val dueMinor: Long get() = (totalMinor - netPaidMinor - creditedMinor).coerceAtLeast(0L)' in repository
    assert 'not contractual due-date aging' in contract.lower()


def test_customer_due_date_aging_remains_blocked_without_due_date_source():
    screen = SCREEN.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("أعمار ديون العملاء"' in screen
    assert 'false, "لا يوجد تاريخ استحقاق موحد محفوظ للفواتير' in screen
