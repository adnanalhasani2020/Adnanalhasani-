from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
DATA_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterData.kt"
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCatalog.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SalesRepository.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_customer_collections_report_uses_successful_nested_payments_and_currency():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("التحصيل حسب العميل"' in screen
    assert 'payment.status.equals("succeeded", true)' in screen
    assert 'payment.createdAt' in screen
    assert 'document.customerId' in screen
    assert 'currencyCode = payment.currencyCode' in screen
    assert 'currencyCode = payment.optString("currency_code")' in REPOSITORY.read_text(encoding="utf-8")
    assert 'calculateCustomerCollectionsByCustomer(' in calculations
    assert 'paymentIds.add(payment.paymentId)' in calculations
    assert 'Collections by customer' in contract


def test_customer_collections_report_blocks_invalid_successful_payment_sources():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert 'reportDateMillis(payment.createdAt) == null' in screen
    assert 'payment.amountMinor <= 0L' in screen
    assert '!payment.currencyCode.matches(Regex("^[A-Z]{3}$"))' in screen
    assert 'payment.currencyCode.matches(Regex("^[A-Z]{3}$"))' in calculations
