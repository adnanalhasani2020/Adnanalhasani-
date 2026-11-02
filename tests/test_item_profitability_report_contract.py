from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_item_profitability_uses_original_sale_item_ids_and_scoped_completed_returns():
    screen = SCREEN.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("ربحية الأصناف بعد المرتجعات"' in screen
    assert 'ItemProfitSaleLineInput(' in screen
    assert 'ItemProfitReturnInput(' in screen
    assert 'sale:commercial_sales!inner(receipt_number,activity_id,customer:' in repository
    assert '&sale.activity_id=eq.$activityId' in repository
    assert 'commercial_sale_items(id,product_name_snapshot' in repository
    assert 'saleItemId: String' in calculations
    assert 'calculateItemProfitability(' in calculations


def test_item_profitability_fails_closed_on_cost_and_return_reconciliation_gaps():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'line.costTotalMinor != null && line.costTotalMinor >= 0L' in calculations
    assert 'itemRefundTotal == approved' in calculations
    assert 'saleById[item.saleItemId]' in calculations
    assert 'calculateRestockedCostMinor(' in calculations
    assert 'Returns can refer to sales from earlier periods' in calculations
    assert 'Item profitability after returns' in contract
