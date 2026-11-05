from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MerchantPurchaseRequestScreen.kt"
NAVIGATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt"


def test_purchase_request_reuses_existing_purchase_order_api():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    assert "commercial_create_purchase_order" in repository
    assert "createCommercialCloudPurchaseOrder" in screen
    assert "CommercialPurchaseOrderLineInput" in screen
    assert "CommercialPurchaseOrderOptions" in repository
    assert "commercial_suppliers" in repository
    assert "commercial_organization_locations" in repository


def test_catalog_purchase_builder_displays_stock_and_unit_and_groups_by_supplier():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    assert "quantity_on_hand" in repository
    assert "quantity_reserved" in repository
    assert "availableQuantity" in repository
    assert "unitName" in repository
    assert "groupBy { it.supplierId }" in screen
    assert "createCommercialCloudPurchaseOrder" in screen
    assert '"draft"' not in screen.lower() or "أوامر الشراء" in screen


def test_purchase_builder_has_navigation_entry():
    navigation = NAVIGATION.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    assert "MerchantPurchaseRequestScreen(" in navigation
    assert "onCreatePurchaseRequest" in navigation
    assert "إنشاء طلب شراء" in more
