from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026260000_customer_portal_order_fulfillment.sql"
DATABASE_TEST = ROOT / "supabase/tests/database/customer_portal_order_fulfillment_test.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
PORTAL = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerOrderFulfillmentScreen.kt"
ACTIVITY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
ORDER_QUEUE = ROOT / "supabase/migrations/20261026261000_customer_portal_order_management.sql"


def test_customer_order_fulfillment_is_explicit_atomic_and_audited():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for marker in (
        "commercial_customer_order_fulfillments",
        "commercial_customer_portal_fulfill_order",
        "commercial_organization_members",
        "customer_order_fulfillment_permission_required",
        "for update",
        "commercial_checkout_sale_multi",
        "customer_order.fulfilled",
        "request_fingerprint",
        "idempotent_replay",
    ):
        assert marker in sql
    assert "security definer" in sql
    assert "set search_path = ''" in sql
    assert "from public, anon" in sql
    assert "to authenticated" in sql


def test_fulfillment_fails_closed_for_unsupported_or_changed_orders():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for marker in (
        "order_type is distinct from 'purchase'",
        "customer_order_type_requires_specialized_fulfillment",
        "customer_order_contains_non_product_or_invalid_lines",
        "customer_order_quantity_not_supported_by_inventory_checkout",
        "customer_order_spans_multiple_activities",
        "customer_order_price_or_offering_changed_requote_required",
        "customer_order_total_mismatch_checkout_rolled_back",
        "customer_credit_limit_exceeded_at_fulfillment",
        "commercial_party_accounts",
        "payment_status is distinct from 'unpaid'",
        "payment_status is distinct from 'credit'",
    ):
        assert marker in sql


def test_android_repository_calls_guarded_fulfillment_rpc():
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert "internal fun fulfillCustomerPortalOrder(" in repository
    assert "/rest/v1/rpc/commercial_customer_portal_fulfill_order" in repository
    for marker in ("p_organization_id", "p_order_id", "p_location_id", "p_idempotency_key"):
        assert marker in repository


def test_database_regression_checks_cover_security_and_atomic_checkout():
    sql = DATABASE_TEST.read_text(encoding="utf-8").lower()
    assert "plan(8)" in sql
    assert "not has_function_privilege('anon'" in sql
    assert "customer_order_fulfillment_permission_required" in sql
    assert "commercial_checkout_sale_multi" in sql
    assert "customer_order_price_or_offering_changed_requote_required" in sql



def test_manager_queue_and_android_screen_make_fulfillment_operational():
    queue = ORDER_QUEUE.read_text(encoding="utf-8").lower()
    screen = SCREEN.read_text(encoding="utf-8")
    activity = ACTIVITY.read_text(encoding="utf-8")
    for marker in (
        "commercial_customer_portal_list_organization_orders",
        "customer_order_management_permission_required",
        "p_organization_id",
        "fulfillment_supported",
        "commercial_guard_customer_order_item_checkout_quantity",
        "product_order_requires_integer_quantity_for_inventory_checkout",
        "service_orders_require_booking_workflow",
    ):
        assert marker in queue
    for marker in (
        "loadCustomerOrderFulfillmentQueue",
        "loadCustomerOrderFulfillmentLocations",
        "fulfillCustomerPortalOrder",
        "اعتماد وإتمام البيع",
        "الطلب لا يحجز المخزون عند إنشائه",
        "تأكيد استلام المبلغ نقدًا",
        "استلمت المبلغ — إتمام البيع",
    ):
        assert marker in screen
    portal = PORTAL.read_text(encoding="utf-8")
    assert "unsupportedFractionalProduct" in portal
    assert 'offerings.filter { it.itemType == "product" }' in portal
    assert "product_order_requires_integer_quantity_for_inventory_checkout" in queue
    assert "CustomerOrderFulfillmentScreen(" in activity
    assert '"طلبات الزبائن"' in activity
    assert "MoreScreen(cloudMode = cloudMode" in activity
    assert 'filter { cloudMode || it != "طلبات الزبائن" }' in activity
    assert "onCustomerOrders = { customerOrderFulfillmentPage = true }" in activity
