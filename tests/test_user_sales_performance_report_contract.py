from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SALES_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SalesRepository.kt"
INVENTORY_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/InventoryRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
DATA_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterData.kt"
MAPPERS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportDomainMappers.kt"
INVENTORY_REPORT_LINES = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportInventoryLines.kt"
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCatalog.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
INVENTORY_CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportInventoryCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_user_sales_performance_uses_persisted_sale_actor_and_separate_currencies():
    repository = SALES_REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + INVENTORY_REPORT_LINES.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8") + MAPPERS.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    assert "customer_id,user_id,receipt_number" in repository
    assert 'userId = row.optString("user_id")' in repository
    assert 'ReportCatalogItem("أداء المبيعات حسب المستخدم"' in screen
    assert 'UserSalesPerformanceInvoiceInput(' in screen
    assert "internal fun calculateUserSalesPerformance(" in calculations
    assert "sumReportAmountsExactly(rows.map { it.totalMinor })" in calculations
    assert 'document.saleStatus.equals("completed", true)' in screen
    assert "Branch and warehouse performance remain separate unfinished work" in contract


def test_user_sales_performance_fails_closed_for_invalid_money_and_duplicate_invoice_ids():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert "invoice.invoiceId.isNotBlank() && seen.add(invoice.invoiceId)" in calculations
    assert 'invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.totalMinor >= 0L' in calculations



def test_stock_movement_user_report_uses_persisted_actor_and_separates_units():
    repository = INVENTORY_REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    inventory_calculations = INVENTORY_CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    assert "created_at,created_by,source_ref,offering_id,movement_type,quantity_delta" in repository
    assert 'createdBy = row.optString("created_by")' in repository
    assert 'ReportCatalogItem("حركة المخزون حسب المستخدم"' in screen
    assert "buildStockMovementUserReportState(" in screen
    assert "StockMovementUserInput(" in inventory_calculations
    assert "internal fun calculateStockMovementUserTotals(" in inventory_calculations
    assert 'it.unitName.trim().ifBlank { "وحدة غير محددة" }' in inventory_calculations
    assert 'movementId.isNotBlank() && seen.add(movement.movementId)' in inventory_calculations
    assert 'by persisted `created_by`, movement type, and recorded unit' in contract



def test_location_inventory_report_uses_organization_locations_and_separates_units():
    repository = INVENTORY_REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    inventory_calculations = INVENTORY_CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")

    assert "loadCommercialCloudLocationInventoryBalances(" in repository
    assert "commercial_inventory_balances" in repository
    assert "commercial_organization_locations" in repository
    assert 'ReportCatalogItem("رصيد المخزون حسب الموقع"' in screen
    assert "buildLocationInventoryReportState(" in screen
    assert "internal fun calculateLocationInventoryBalanceTotals(" in inventory_calculations
    assert "seen.add(balance.locationId to balance.offeringId)" in inventory_calculations
    assert "balance.unitName.trim()" in inventory_calculations
    assert 'by location and recorded unit' in contract
