from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261027200000_customer_portal_multi_service_booking.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
PORTAL = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt"
HISTORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalHistoryScreen.kt"
DATABASE_TEST = ROOT / "supabase/tests/database/customer_portal_multi_service_booking_test.sql"


def test_multi_service_booking_is_atomic_and_server_priced():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for marker in (
        "commercial_customer_portal_create_multi_service_booking",
        "commercial_customer_portal_create_order",
        "commercial_bookings",
        "p_lines",
        "unit_price_minor",
        "line_total_minor",
        "price_minor",
        "booking_resource_capacity_exceeded",
        "v_requested_capacity",
        "v_used_capacity",
        "sum(oi.quantity)",
        "for update",
        "pg_advisory_xact_lock",
        "customer_credit_not_authorized",
        "booking_service_not_available",
        "duplicate_booking_offering",
        "idempotency_key_reused_with_different_request",
    ):
        assert marker in sql
    assert "security definer" in sql
    assert "set search_path = ''" in sql
    assert "from public, anon" in sql
    assert "to authenticated" in sql


def test_android_sends_all_service_lines_to_multi_service_rpc():
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert "submitCustomerPortalMultiServiceBooking(" in repository
    assert "/rest/v1/rpc/commercial_customer_portal_create_multi_service_booking" in repository
    for marker in ('put("p_lines", payloadLines)', '"offering_id"', '"quantity"', '"p_idempotency_key"'):
        assert marker in repository


def test_booking_ui_supports_multi_line_quantities_and_itemized_totals():
    portal = PORTAL.read_text(encoding="utf-8")
    for marker in (
        'offerings.filter { it.itemType == "service" }.forEach { service ->',
        "bookingCart",
        "bookingEstimatedTotal",
        "submitCustomerPortalMultiServiceBooking",
        "فاتورة الحجز الذكية",
        "تأكيد الحجز وإصدار الفاتورة",
        "كل البنود تُحفظ تحت حجز واحد",
        "bookingQuantitiesValid",
    ):
        assert marker in portal
    history = HISTORY.read_text(encoding="utf-8")
    for marker in ("bookingOrder?.items?.forEach", "سعر الوحدة", "إجمالي البند", "إجمالي الفاتورة"):
        assert marker in history


def test_database_regression_checks_cover_privileges_capacity_and_idempotency():
    sql = DATABASE_TEST.read_text(encoding="utf-8").lower()
    assert "plan(8)" in sql
    assert "has_function_privilege(" in sql
    assert "'anon'" in sql
    assert "commercial_customer_portal_create_multi_service_booking" in sql
    assert "booking_resource_capacity_exceeded" in sql
    assert "idempotency_key_reused_with_different_request" in sql
    assert "line_total_minor" in sql
