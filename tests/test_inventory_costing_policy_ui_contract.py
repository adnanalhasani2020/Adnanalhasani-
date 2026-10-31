from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudStockAlertsScreen.kt"


def test_costing_policy_load_and_save_are_scoped_to_org_and_location():
    source = REPOSITORY.read_text(encoding="utf-8")
    assert "loadCommercialInventoryCostingPolicy" in source
    assert "saveCommercialInventoryCostingPolicy" in source
    assert "organization_id=eq.$orgId" in source
    assert "location_id=eq.$locationId" in source
    assert "location_id=is.null" in source
    assert 'setOf("fifo", "lifo", "weighted_average")' in source


def test_costing_policy_changes_do_not_rewrite_historical_sale_allocations():
    source = REPOSITORY.read_text(encoding="utf-8")
    assert "sale allocations retain their original method snapshot" in source
    assert "commercial_inventory_costing_policies" in source
    assert "commercial_inventory_cost_allocations" not in source[source.index("internal fun saveCommercialInventoryCostingPolicy"):source.index("internal fun loadCommercialCloudCostLayerSummary")]


def test_stock_screen_exposes_policy_controls_and_cost_layer_coverage():
    screen = SCREEN.read_text(encoding="utf-8")
    assert "طريقة تقييم تكلفة المخزون" in screen
    assert 'label = { Text("FIFO") }' in screen
    assert 'label = { Text("LIFO") }' in screen
    assert "حفظ طريقة التكلفة" in screen
    assert "ملخص طبقات التكلفة النشطة" in screen
    assert "لا تُخمن لها تكلفة" in screen
    assert "ملخص التغطية أعلاه يخص الموقع الحالي فقط" in screen
