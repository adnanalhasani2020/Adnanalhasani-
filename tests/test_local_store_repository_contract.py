from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalStoreRepository.kt"


def test_local_store_repository_preserves_legacy_preference_and_json_contract():
    repository = REPOSITORY.read_text(encoding="utf-8")
    for key in (
        '"commercial_store_local_v1"',
        '"products_json"',
        '"sales_json"',
        '"settlements_json"',
        '"expenses_json"',
    ):
        assert key in repository
    for field in (
        '"name"', '"category"', '"stock"', '"price"', '"costPrice"', '"catalogCode"',
        '"barcode"', '"unitCode"', '"imageUrl"', '"description"', '"brand"',
        '"manufacturer"', '"countryOfManufacture"', '"createdAt"', '"createdBy"',
        '"unitVariantsJson"', '"reorderPoint"', '"number"', '"product"', '"quantity"',
        '"total"', '"credit"', '"customer"', '"paid"', '"unitCost"', '"baseQuantitySold"',
        '"currencyCode"', '"status"', '"invoiceNumber"', '"amount"', '"timestamp"',
        '"id"', '"note"',
    ):
        assert field in repository


def test_local_store_repository_keeps_organization_scoped_keys_and_async_commit():
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert 'getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)' in repository
    assert 'getString("organization_id", null)' in repository
    assert '"${key}_${organizationId}"' in repository
    assert '.apply()' in repository


def test_main_activity_delegates_local_storage_without_changing_local_mode_gate():
    main = MAIN.read_text(encoding="utf-8")
    for operation in (
        "loadProducts", "saveProducts", "loadSales", "loadSettlements",
        "loadExpenses", "saveExpenses", "saveSettlements", "saveSales",
    ):
        assert f"LocalStoreRepository.{operation}(" in main
    assert "if (!cloudContextHasAny) addAll(LocalStoreRepository.loadSales(context))" in main
    assert "if (!cloudContextHasAny) addAll(LocalStoreRepository.loadProducts(context))" in main
