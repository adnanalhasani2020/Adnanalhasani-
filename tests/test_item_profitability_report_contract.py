from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
DATA_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterData.kt"
MAPPERS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportDomainMappers.kt"
PROFITABILITY_VALIDATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportProfitabilityValidation.kt"
SUPPLIER_VALIDATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportSupplierValidation.kt"
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCatalog.kt"
CATALOG_SECTION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCatalogSection.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReturnsRepository.kt"
CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportCalculations.kt"
COST_PROFIT_CALCULATIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CostProfitCalculations.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


def test_item_profitability_uses_original_sale_item_ids_and_scoped_completed_returns():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8") + MAPPERS.read_text(encoding="utf-8") + PROFITABILITY_VALIDATION.read_text(encoding="utf-8") + SUPPLIER_VALIDATION.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("ربحية الأصناف بعد المرتجعات"' in screen
    assert 'ItemProfitSaleLineInput(' in screen
    assert 'ItemProfitReturnInput(' in screen
    assert 'sale:commercial_sales!inner(receipt_number,activity_id,customer:' in repository
    assert '&sale.activity_id=eq.$activityId' in repository
    assert 'commercial_sale_items(id,product_name_snapshot' in repository
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    assert 'saleItemId: String' in cost_profit_calculations
    assert 'calculateItemProfitability(' in cost_profit_calculations


def test_item_profitability_fails_closed_on_cost_and_return_reconciliation_gaps():
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'line.costTotalMinor != null && line.costTotalMinor >= 0L' in cost_profit_calculations
    assert 'itemRefundTotal == approved' in cost_profit_calculations
    assert 'saleById[item.saleItemId]' in cost_profit_calculations
    assert 'calculateRestockedCostMinor(' in cost_profit_calculations
    assert 'Returns can refer to sales from earlier periods' in cost_profit_calculations
    assert 'Item profitability after returns' in contract


def test_supplier_purchase_summary_is_line_and_header_reconciled():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("المشتريات حسب المورد"' in screen
    assert 'SupplierPurchaseInvoiceInput(' in MAPPERS.read_text(encoding="utf-8")
    assert 'calculateSupplierPurchaseSummary(' in calculations
    assert 'sumReportAmountsExactly(invoice.lines.map { it.lineTotalMinor })' in SUPPLIER_VALIDATION.read_text(encoding="utf-8")
    assert 'Purchases by supplier' in contract

def test_item_profitability_rejects_unbounded_completed_dates_and_refund_state_mismatch():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    assert 'completed", true) && reportDateMillis(it.createdAt) == null' in PROFITABILITY_VALIDATION.read_text(encoding="utf-8")
    assert 'completed", true) && reportDateMillis(it.completedAt) == null' in PROFITABILITY_VALIDATION.read_text(encoding="utf-8")
    assert 'it.refundStatus.equals("refunded", true) && !it.status.equals("completed", true)' in PROFITABILITY_VALIDATION.read_text(encoding="utf-8")

def test_daily_sales_trend_uses_completed_invoices_and_currency_separated_totals():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("اتجاه المبيعات اليومي"' in screen
    assert 'DailySalesInvoiceInput(' in MAPPERS.read_text(encoding="utf-8")
    assert 'calculateDailySalesTrend(' in calculations
    assert 'document.saleStatus.equals("completed", true)' in screen
    assert 'ReportCatalogItem("الخصومات على فواتير المبيعات"' in screen
    catalog_section = CATALOG_SECTION.read_text(encoding="utf-8")
    assert "selectableReportTitle(report.title)?.let(onReportSelected)" in catalog_section
    assert "onReportSelected = { selectedReport = it }" in screen
    assert 'Daily sales trend' in contract

def test_item_profitability_matches_return_to_original_sale_and_blocks_tax_inclusive_refunds():
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'saleLine.saleId == saleReturn.saleId' in cost_profit_calculations
    assert 'saleLine.saleTaxMinor == 0L' in cost_profit_calculations
    assert 'tax-inclusive refund as net revenue' in contract

def test_item_profitability_does_not_invent_invoice_discount_allocation():
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'line.saleDiscountMinor == 0L' in cost_profit_calculations
    assert 'does not distribute them by an invented proportional rule' in contract

def test_item_profitability_blocks_returns_from_discounted_historical_sales():
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    assert 'saleLine.saleDiscountMinor == 0L' in cost_profit_calculations
    assert 'يرتبط المرتجع بفاتورة لها خصم إجمالي غير موزع' in cost_profit_calculations

def test_item_profitability_reconciles_sale_lines_to_invoice_subtotal():
    screen = SCREEN.read_text(encoding="utf-8") + DATA_STATE.read_text(encoding="utf-8") + CATALOG.read_text(encoding="utf-8")
    validation = PROFITABILITY_VALIDATION.read_text(encoding="utf-8")
    assert 'sumReportAmountsExactly(document.lines.map { it.lineTotalMinor })' in validation
    assert 'document.lines.isEmpty()' in validation
    assert 'document.discountMinor > document.subtotalMinor' in validation

def test_item_profitability_requires_explicit_return_item_amount_and_restock_fields():
    cost_profit_calculations = COST_PROFIT_CALCULATIONS.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert 'refundAmountPresent = item.has("refund_amount_minor")' in repository
    assert 'restockFlagPresent = item.has("restock")' in repository
    assert 'restockedQuantityPresent = item.has("restocked_quantity")' in repository
    assert 'item.refundAmountPresent && item.restockFlagPresent && item.restockedQuantityPresent' in cost_profit_calculations
