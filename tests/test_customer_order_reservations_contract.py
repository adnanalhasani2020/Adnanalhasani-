from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261105140000_commercial_customer_order_reservations.sql"


def test_reservation_is_separate_from_physical_stock_and_tenant_scoped():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "quantity_reserved=b.quantity_reserved+v_item.quantity" in sql
    assert "quantity_on_hand-b.quantity_reserved>=v_item.quantity" in sql
    assert "physical_on_hand_unchanged" in sql
    assert "organization_id=p_organization_id" in sql
    assert "for update of b" in sql


def test_reservation_expiry_policies_release_or_notify_daily():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "'release_stock','keep_reserved_notify','cancel_order'" in sql
    assert "next_reminder_at=now()+interval '1 day'" in sql
    assert "commercial_user_notifications" in sql
    assert "quantity_reserved=b.quantity_reserved-v_item.quantity" in sql
    assert "skip locked" in sql


def test_reserved_fulfillment_releases_only_inside_same_checkout_transaction():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    wrapper = sql.split("create or replace function public.commercial_customer_portal_fulfill_reserved_order", 1)[1]
    assert "set status='consumed'" in wrapper
    assert "commercial_customer_portal_fulfill_order(" in wrapper
    assert "active_order_reservation_not_found" in wrapper
    assert "revoke all on function" in wrapper
    assert "to authenticated" in wrapper


def test_rpc_grants_do_not_expose_internal_reservation_tables():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "revoke all on table public.commercial_customer_order_reservations from public,anon,authenticated" in sql
    assert "revoke all on table public.commercial_customer_order_reservation_items from public,anon,authenticated" in sql
    assert "set search_path = ''" in sql
    assert "reservation_idempotency_key_reused" in sql

def test_android_merchant_queue_reserves_and_fulfills_reserved_orders():
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalRepository.kt").read_text(encoding="utf-8")
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerOrderFulfillmentScreen.kt").read_text(encoding="utf-8")
    assert "commercial_customer_portal_reserve_order" in repository
    assert "commercial_customer_portal_process_expired_order_reservations" in repository
    assert "commercial_customer_portal_fulfill_reserved_order" in repository
    assert "اعتماد الحجز وحجز الكمية" in screen
    assert "إبقاء الحجز وإرسال تذكير يومي" in screen
    assert "release_stock" in screen and "cancel_order" in screen

def test_reservation_dialog_requires_expiry_and_exposes_all_expiry_policies():
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerOrderFulfillmentScreen.kt").read_text(encoding="utf-8")
    assert "reservationTarget?.let" in screen
    assert "validHours" in screen
    assert "SimpleDateFormat" in screen
    assert "release_stock" in screen
    assert "keep_reserved_notify" in screen
    assert "cancel_order" in screen
