from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261028010000_merchant_customer_order_management.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerOrderFulfillmentScreen.kt"


def test_merchant_order_lifecycle_is_server_authorized_atomic_and_audited():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for marker in (
        "commercial_customer_portal_list_organization_orders_v2",
        "commercial_customer_portal_list_organization_bookings",
        "commercial_customer_portal_decide_order",
        "commercial_customer_portal_decide_booking",
        "commercial_customer_portal_set_order_hidden",
        "commercial_customer_portal_set_booking_hidden",
        "commercial_customer_portal_edit_order_lines",
        "commercial_customer_portal_list_editable_offerings",
        "commercial_audit_events",
        "merchant_hidden_at",
        "merchant_reviewed_by",
        "rejection_reason_required",
        "p_decision is null",
        "booking_resource_capacity_exceeded",
        "customer_booking_order_decision_race_retry",
        "for update",
        "set search_path = ''",
        "from public,anon",
        "to authenticated",
    ):
        assert marker in sql


def test_order_editor_uses_server_pricing_and_only_allows_pending_orders():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    edit = sql.split("create or replace function public.commercial_customer_portal_edit_order_lines", 1)[1].split("$function$;", 1)[0]
    assert "v_order.status<>'pending'" in edit
    assert "commercial_customer_order_fulfillments" in edit
    assert "commercial_offerings" in edit
    assert "f.price_minor" in edit
    assert "v_total::bigint" in edit
    assert "previous_total_minor" in edit and "'before',v_before" in edit and "'after',v_after" in edit
    assert "p_lines" in edit
    assert "unit_price_minor" not in edit.split("for v_line in select value from jsonb_array_elements(p_lines) loop", 1)[0]


def test_merchant_android_screen_exposes_search_filters_booking_decisions_hide_and_fulfillment():
    screen = SCREEN.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    for marker in ("بحث برقم الطلب", "الحجوزات", "إخفاء من القائمة", "إظهار مجددًا", "قبول الطلب", "رفض الطلب", "تأكيد الحجز", "رفض الحجز", "اعتماد وإتمام البيع", "التفاصيل"):
        assert marker in screen
    for marker in (
        "loadMerchantBookings(context,hidden)",
        "decideMerchantCustomerOrder(",
        "decideMerchantCustomerBooking(",
        "setMerchantCustomerOrderHidden(",
        "setMerchantCustomerBookingHidden(",
        "saveMerchantCustomerOrderLines(",
        "loadMerchantEditableOfferings(",
        "commercial_customer_portal_edit_order_lines",
    ):
        assert marker in repository
