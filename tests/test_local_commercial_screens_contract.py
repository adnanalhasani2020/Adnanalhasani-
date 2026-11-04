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


def test_local_sale_and_product_dialogs_have_a_dedicated_owner():
    main = MAIN.read_text(encoding="utf-8")
    dialogs = (ANDROID / "LocalProductDialogs.kt").read_text(encoding="utf-8")
    for name in ("SaleEntryDialog", "EditProductDialog", "AddProductDialog"):
        assert f"internal fun {name}(" in dialogs
        assert f"internal fun {name}(" not in main


def test_main_activity_is_only_the_android_entry_point():
    main = MAIN.read_text(encoding="utf-8")
    assert "class MainActivity : ComponentActivity" in main
    assert "StoreApp(applicationContext)" in main
    for name in (
        "SaleEntryDialog",
        "EditProductDialog",
        "AddProductDialog",
        "MoreScreen",
        "ExpensesScreen",
        "ExpenseEntryDialog",
        "ReportsScreen",
        "CommercialCloudReportsScreen",
        "DebtScreen",
        "SettlementDialog",
    ):
        assert f"fun {name}(" not in main


def test_local_operational_screens_have_a_dedicated_owner():
    screens = (ANDROID / "LocalOperationalScreens.kt").read_text(encoding="utf-8")
    for name in (
        "MoreScreen",
        "ExpensesScreen",
        "ExpenseEntryDialog",
        "ReportsScreen",
        "CommercialCloudReportsScreen",
        "DebtScreen",
        "SettlementDialog",
    ):
        assert f"internal fun {name}(" in screens


def test_shared_store_palette_has_a_single_owner():
    palette = (ANDROID / "StorePalette.kt").read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    for name in ("Ink", "Green", "Mint", "Canvas", "Muted", "Orange", "Border"):
        assert f"internal val {name} =" in palette
    assert "private val Green =" not in main
    assert "private val Canvas =" not in main
