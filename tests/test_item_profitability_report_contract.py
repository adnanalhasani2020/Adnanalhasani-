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


def test_supplier_purchase_summary_is_line_and_header_reconciled():
    screen = SCREEN.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("المشتريات حسب المورد"' in screen
    assert 'SupplierPurchaseInvoiceInput(' in screen
    assert 'calculateSupplierPurchaseSummary(' in calculations
    assert 'sumReportAmountsExactly(invoice.lines.map { it.lineTotalMinor })' in screen
    assert 'Purchases by supplier' in contract

def test_item_profitability_rejects_unbounded_completed_dates_and_refund_state_mismatch():
    screen = SCREEN.read_text(encoding="utf-8")
    assert 'completed", true) && reportDateMillis(it.createdAt) == null' in screen
    assert 'completed", true) && reportDateMillis(it.completedAt) == null' in screen
    assert 'it.refundStatus.equals("refunded", true) && !it.status.equals("completed", true)' in screen

def test_daily_sales_trend_uses_completed_invoices_and_currency_separated_totals():
    screen = SCREEN.read_text(encoding="utf-8")
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'ReportCatalogItem("اتجاه المبيعات اليومي"' in screen
    assert 'DailySalesInvoiceInput(' in screen
    assert 'calculateDailySalesTrend(' in calculations
    assert 'document.saleStatus.equals("completed", true)' in screen
    assert '"اتجاه المبيعات اليومي", "الخصومات على فواتير المبيعات"' in screen
    assert '-> selectedReport = report.title' in screen
    assert 'Daily sales trend' in contract

def test_item_profitability_matches_return_to_original_sale_and_blocks_tax_inclusive_refunds():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'saleLine.saleId == saleReturn.saleId' in calculations
    assert 'saleLine.saleTaxMinor == 0L' in calculations
    assert 'tax-inclusive refund as net revenue' in contract

def test_item_profitability_does_not_invent_invoice_discount_allocation():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    assert 'line.saleDiscountMinor == 0L' in calculations
    assert 'does not distribute them by an invented proportional rule' in contract

def test_item_profitability_blocks_returns_from_discounted_historical_sales():
    calculations = CALCULATIONS.read_text(encoding="utf-8")
    assert 'saleLine.saleDiscountMinor == 0L' in calculations
    assert 'يرتبط المرتجع بفاتورة لها خصم إجمالي غير موزع' in calculations
