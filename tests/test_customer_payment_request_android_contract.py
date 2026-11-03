from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text(encoding="utf-8")
CUSTOMER_SCREEN = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalPaymentRequestsScreen.kt").read_text(encoding="utf-8")
MERCHANT_SCREEN = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
PORTAL_SCREEN = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8")


def test_customer_repository_uses_authenticated_portal_rpcs_and_retry_keys():
    assert '"/rest/v1/rpc/commercial_customer_portal_list_outstanding_sales"' in REPOSITORY
    assert '"/rest/v1/rpc/commercial_customer_portal_list_my_payment_requests"' in REPOSITORY
    assert '"/rest/v1/rpc/commercial_customer_portal_submit_payment_request"' in REPOSITORY
    assert '"/rest/v1/rpc/commercial_customer_portal_update_payment_request"' in REPOSITORY
    assert 'customerPortalAccessToken(context)' in REPOSITORY
    assert 'commercial_customer_payment_request_keys_v1' in REPOSITORY
    assert 'getCustomerPaymentRequestActionKey' in REPOSITORY


def test_customer_screen_keeps_claims_pending_until_merchant_approval():
    assert "قيد انتظار اعتماد التاجر" in CUSTOMER_SCREEN
    assert "لن يتغير رصيدك قبل اعتماد التاجر" in CUSTOMER_SCREEN
    assert "updateCustomerPortalPaymentRequest" in CUSTOMER_SCREEN
    assert "submitCustomerPortalPaymentRequest" in CUSTOMER_SCREEN
    assert "parseCustomerRequestAmount" in CUSTOMER_SCREEN
    assert "commercial_record_customer_payment" not in CUSTOMER_SCREEN


def test_merchant_screen_requires_amount_match_and_supports_review_decisions():
    assert "loadCommercialCloudCustomerPaymentRequests(context, \"pending\")" in MERCHANT_SCREEN
    assert "reviewCommercialCloudCustomerPaymentRequest" in MERCHANT_SCREEN
    assert 'parseMerchantPaymentRequestAmount(confirmedAmount, requestTarget.currencyCode) == requestTarget.amountMinor' in MERCHANT_SCREEN
    assert '"request_changes"' in MERCHANT_SCREEN
    assert '"reject"' in MERCHANT_SCREEN
    assert '"approve"' in MERCHANT_SCREEN


def test_customer_portal_and_main_activity_expose_payment_request_screen():
    assert "onPaymentRequests" in PORTAL_SCREEN
    assert "Icons.Default.ReceiptLong" in PORTAL_SCREEN
    assert "CustomerPortalPaymentRequestsScreen(" in MAIN
    assert "customerPaymentRequestsPage" in MAIN


def test_merchant_confirmation_amount_uses_currency_units_not_minor_units():
    debt = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
    calculations = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt").read_text(encoding="utf-8")
    assert "parseMerchantPaymentRequestAmount" in debt
    assert "internal fun parseMerchantPaymentRequestAmount" in calculations
    assert "internal fun parseMerchantPaymentRequestAmount" not in debt
    assert 'cloudDebtInput(request.amountMinor, request.currencyCode)' in debt
    assert 'label = { Text("المبلغ الفعلي الذي استلمته (" + requestTarget.currencyCode + ")") }' in debt
    assert "confirmedAmount.toLongOrNull()" not in debt
