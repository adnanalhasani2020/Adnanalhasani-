from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026260000_customer_portal_order_fulfillment.sql"
DATABASE_TEST = ROOT / "supabase/tests/database/customer_portal_order_fulfillment_test.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
PORTAL = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt"


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
