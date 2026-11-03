from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
MAIN = ANDROID / "MainActivity.kt"
DASHBOARD = ANDROID / "DashboardScreen.kt"
SALES = ANDROID / "LocalSalesScreen.kt"
INVENTORY = ANDROID / "LocalInventoryScreen.kt"
COMPONENTS = ANDROID / "StoreUiComponents.kt"


def test_local_feature_screens_have_single_clear_owners():
    main = MAIN.read_text(encoding="utf-8")
    dashboard = DASHBOARD.read_text(encoding="utf-8")
    sales = SALES.read_text(encoding="utf-8")
    inventory = INVENTORY.read_text(encoding="utf-8")
    assert "internal fun Dashboard(" in dashboard
    assert "internal fun SalesScreen(" in sales
    assert "internal fun InventoryScreen(" in inventory
    assert "private fun Dashboard(" not in main
    assert "private fun SalesScreen(" not in main
    assert "private fun InventoryScreen(" not in main


def test_local_screens_share_components_without_moving_business_rules_into_ui():
    components = COMPONENTS.read_text(encoding="utf-8")
    assert "internal fun Metric(" in components
    assert "internal fun Heading(" in components
    assert "internal fun ProductRow(" in components
    assert "internal fun SaleRow(" in components
    assert "isCommercialStockLow(" in components
    assert "formatCommercialStockQuantity(" in components
    assert "calculateCostProfitReportTotals" not in components
    assert "calculateSupplierInvoicePriceVariance" not in components
