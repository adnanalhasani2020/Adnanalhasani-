from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / "supabase/migrations/20261106102440_general_customer_supply_requests_forward_20261106.sql"
PORTAL = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalRepository.kt"
MERCHANT = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerSupplyRequestsScreen.kt"
NAVIGATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt"


def test_general_supply_request_is_separate_from_invoice_transfer_and_sales():
    sql = SQL.read_text(encoding="utf-8").lower()
    assert "commercial_customer_supply_requests" in sql
    assert "commercial_invoice_purchase_requests" not in sql
    assert "commercial_customer_orders" not in sql
    assert "request_fingerprint" in sql
    assert "unique(submitted_by,idempotency_key)" in sql
    assert "customer_not_linked_to_organization" in sql
    assert "customer_credit_not_authorized" in sql


def test_request_lifecycle_and_customer_feedback_are_supported():
    sql = SQL.read_text(encoding="utf-8").lower()
    for status in ("pending", "accepted", "rejected", "change_requested", "ignored", "deferred", "converted"):
        assert status in sql
    for rpc in (
        "commercial_customer_portal_submit_supply_request",
        "commercial_customer_portal_list_my_supply_requests",
        "commercial_customer_portal_list_organization_supply_requests",
        "commercial_customer_portal_review_supply_request",
        "commercial_customer_portal_resubmit_supply_request",
        "commercial_customer_portal_convert_supply_request_to_purchase",
    ):
        assert rpc in sql
    assert "commercial_user_notifications" in sql
    assert "commercial_customer_supply_request_events" in sql


def test_purchase_conversion_is_draft_and_has_no_stock_or_accounting_effect():
    sql = SQL.read_text(encoding="utf-8").lower()
    conversion = sql.split("create or replace function public.commercial_customer_portal_convert_supply_request_to_purchase", 1)[1]
    assert "'draft'" in conversion
    assert "p_unit_cost_minor,null" in conversion
    assert "'stock_received',false" in conversion
    assert "'accounting_posted',false" in conversion
    assert "commercial_purchase_order_items" in conversion


def test_android_customer_and_merchant_flows_are_wired():
    portal = PORTAL.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    merchant = MERCHANT.read_text(encoding="utf-8")
    navigation = NAVIGATION.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    assert "طلب توفير صنف غير موجود" in portal
    assert "submitCustomerSupplyRequest" in portal
    assert "commercial_customer_portal_submit_supply_request" in repository
    assert "commercial_customer_portal_list_organization_supply_requests" in merchant
    assert "commercial_customer_portal_review_supply_request" in merchant
    assert "commercial_customer_portal_convert_supply_request_to_purchase" in merchant
    assert "CustomerSupplyRequestsScreen(" in navigation
    assert "onCustomerSupplyRequests" in more
