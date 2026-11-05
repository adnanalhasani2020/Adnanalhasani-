from pathlib import Path
from catalog_source_helpers import catalog_android_source

ROOT = Path(__file__).resolve().parents[1]
GATES = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReleaseGates.kt"
SALE_COMPOSER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSaleComposerScreen.kt"
RETURNS_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt"
QUANTITY_MATH = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt"
INTEGER_MIGRATION = ROOT / "supabase/migrations/20261105123000_commercial_integer_quantity_enforcement.sql"


def test_sale_and_return_quantity_parser_rejects_all_fractional_input():
    math = QUANTITY_MATH.read_text(encoding="utf-8")
    assert 'Regex("^[0-9]{1,6}$")' in math
    assert "quantity.stripTrailingZeros().scale() <= 0" in math
    assert "quantity.stripTrailingZeros().scale() > 0" in math
    assert "0.5 أو 1.25" not in SALE_COMPOSER.read_text(encoding="utf-8")


def test_sale_and_return_screens_preserve_fractional_input_for_rejection():
    sale = SALE_COMPOSER.read_text(encoding="utf-8")
    returns = RETURNS_SCREEN.read_text(encoding="utf-8")
    assert "raw.any { Character.digit(it, 10) < 0 }" in sale
    assert "raw.any { Character.digit(it, 10) < 0 }" in returns
    assert "raw.map { ch -> ('0'.code + Character.digit(ch, 10)).toChar() }" in sale
    assert "raw.map { ch -> ('0'.code + Character.digit(ch, 10)).toChar() }" in returns
    assert "الكمية (عدد صحيح فقط)" in sale
    assert "عدد صحيح" in returns


def test_cloud_rpcs_and_numeric_storage_are_integer_only():
    migration = INTEGER_MIGRATION.read_text(encoding="utf-8")
    assert "commercial_checkout_sale_multi_base_units" in migration
    assert "commercial_request_sale_return_base_units" in migration
    assert "v_qty<>trunc(v_qty)" in migration
    assert "p_quantity<>trunc(p_quantity)" in migration
    assert "fractional_historical_quantity_requires_reconciliation" in migration
    assert "ADD CONSTRAINT" in migration
    assert "IN ('numeric', 'real', 'double precision')" in migration
    assert "commercial_purchase_order_items" in migration
    assert "commercial_sale_items" in migration
    assert "CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_order(" in migration
    assert "CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_delegated_order(" in migration
    assert "CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_multi_service_booking(" in migration
    assert "commercial_private.commercial_create_inventory_item" in migration
    assert "commercial_catalog_create_inventory_item_from_global" in migration
    assert "p_reorder_point <> trunc(p_reorder_point)" in migration
    assert "commercial_private.commercial_record_supplier_invoice(" in migration
    supplier_invoice_rpc = migration.split("CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_invoice(", 1)[1].split("$function$;", 1)[0]
    assert "v_qty <> trunc(v_qty)" in supplier_invoice_rpc
    assert "v_qty <> trunc(v_qty)" in migration


def test_payment_request_screens_do_not_call_unpublished_production_rpcs():
    gates = GATES.read_text(encoding="utf-8")
    customer = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalPaymentRequestsScreen.kt").read_text(encoding="utf-8")
    merchant = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY = false" in gates
    assert "if (!COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY)" in customer
    assert "check(COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY)" in merchant
    assert "لن يتم إرسال أي طلب" in customer


def test_supplier_invoice_and_unmapped_item_creation_remain_gated_until_migrations():
    gates = GATES.read_text(encoding="utf-8")
    supplier = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupplierInvoiceEntryDialog.kt").read_text(encoding="utf-8")
    catalog = catalog_android_source()
    assert "COMMERCIAL_CLOUD_SUPPLIER_INVOICE_DATES_RPC_READY = false" in gates
    assert "COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY = false" in gates
    assert "COMMERCIAL_CLOUD_SUPPLIER_INVOICE_DATES_RPC_READY" in supplier
    assert "COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY" in catalog


def test_return_unit_conversion_remains_bound_to_original_invoice_snapshot():
    gates = GATES.read_text(encoding="utf-8")
    screen = RETURNS_SCREEN.read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_RETURN_UNIT_SNAPSHOT_RPC_READY = false" in gates
    assert "returnUnitSnapshotBlocked" in screen
    assert "&& !returnUnitSnapshotBlocked" in screen
    assert "معامل الوحدة الحالي قد يختلف عن معامل الفاتورة وقت البيع" in screen
