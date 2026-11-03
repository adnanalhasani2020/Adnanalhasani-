from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GATES = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReleaseGates.kt"
SALE_COMPOSER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSaleComposerScreen.kt"
RETURNS_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt"


def test_fractional_cloud_quantity_gate_matches_unmigrated_production_rpc():
    gates = GATES.read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_FRACTIONAL_QUANTITY_RPC_READY = false" in gates
    assert "20261103020000" in gates
    assert "20261103020100" in gates


def test_sale_composer_blocks_fractional_quantity_before_cloud_rpc():
    screen = SALE_COMPOSER.read_text(encoding="utf-8")
    assert "hasFractionalSellingQuantity" in screen
    assert "fractionalSellingQuantityBlocked" in screen
    assert "&& !fractionalSellingQuantityBlocked" in screen
    assert "قاعدة الإنتاج الحالية لا تقبل كميات البيع العشرية" in screen
    assert screen.index("fractionalSellingQuantityBlocked") < screen.index("checkoutCommercialCloudSaleMulti(")


def test_return_screen_blocks_fractional_quantity_before_cloud_rpc():
    screen = RETURNS_SCREEN.read_text(encoding="utf-8")
    assert "hasFractionalReturnQuantity" in screen
    assert "fractionalReturnQuantityBlocked" in screen
    assert "!fractionalReturnQuantityBlocked" in screen
    assert "قاعدة الإنتاج الحالية لا تقبل كميات مرتجعة عشرية" in screen
    assert screen.index("fractionalReturnQuantityBlocked") < screen.index("requestCommercialCloudSaleReturn(")

def test_payment_request_screens_do_not_call_unpublished_production_rpcs():
    gates = GATES.read_text(encoding="utf-8")
    customer = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalPaymentRequestsScreen.kt").read_text(encoding="utf-8")
    merchant = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY = false" in gates
    assert "if (!COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY)" in customer
    assert "check(COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY)" in merchant
    assert "لن يتم إرسال أي طلب" in customer


def test_supplier_invoice_and_unmapped_item_creation_are_gated_until_migrations():
    gates = GATES.read_text(encoding="utf-8")
    supplier = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupplierInvoiceEntryDialog.kt").read_text(encoding="utf-8")
    catalog = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_SUPPLIER_INVOICE_DATES_RPC_READY = false" in gates
    assert "COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY = false" in gates
    assert "COMMERCIAL_CLOUD_SUPPLIER_INVOICE_DATES_RPC_READY" in supplier
    assert "COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY" in catalog
