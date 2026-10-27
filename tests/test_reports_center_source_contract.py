from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"
COLLECTION_REPLAY_MIGRATION = ROOT / "supabase/migrations/20261027221500_harden_customer_collection_replay_posting.sql"


class ReportsCenterSourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.contract = CONTRACT.read_text(encoding="utf-8")
        cls.collection_replay_migration = COLLECTION_REPLAY_MIGRATION.read_text(encoding="utf-8")

    def test_cloud_operational_reports_use_authoritative_sources(self):
        for source in (
            "loadCommercialCloudSaleDocuments(context)",
            "loadCommercialCloudPurchaseDocuments(context)",
            "loadCommercialCloudSaleReturns(context)",
            'loadCommercialCloudRecords(context, "payments")',
            "loadCommercialCloudExpenses(context)",
        ):
            with self.subTest(source=source):
                self.assertIn(source, self.screen)

    def test_ledger_projection_is_organization_scoped_and_posted_only(self):
        loader = self.repository.split(
            "internal fun loadCommercialCloudPostedJournalLines", 1
        )[1]
        self.assertIn("status=eq.posted", loader)
        self.assertIn("organization_id=eq.$orgId", loader)
        self.assertIn("commercial_journal_lines", loader)
        self.assertIn("commercial_ledger_accounts", loader)
        self.assertIn("debit_minor", loader)
        self.assertIn("credit_minor", loader)

    def test_limited_customer_and_supplier_statements_are_not_mislabeled_as_full_financial_statements(self):
        self.assertIn('ReportCatalogItem("أرصدة العملاء وكشف الحساب"', self.screen)
        self.assertIn('ReportCatalogItem("كشف حركات الموردين"', self.screen)
        self.assertIn('ReportCatalogItem("الرصيد المحاسبي الكامل للعملاء"', self.screen)
        self.assertIn('ReportCatalogItem("أرصدة الموردين والمستحقات", "الموردون والمستحقات", "سطور دفتر الأستاذ المرحلة المرتبطة بحساب مراقبة المورد", cloudMode', self.screen)
        self.assertIn('ReportCatalogItem("الرصيد المحاسبي الكامل للعملاء", "العملاء والديون", "سطور دفتر الأستاذ المرحلة المرتبطة بحساب مراقبة العميل", cloudMode', self.screen)
        self.assertIn("Full customer ledger statement", self.contract)
        self.assertIn("Full supplier ledger statement", self.contract)
        self.assertIn("historical inventory valuation and inventory-to-GL valuation reconciliation", self.contract)

    def test_xlsx_export_is_real_ooxml_and_right_to_left(self):
        self.assertIn("ZipOutputStream(output)", self.screen)
        self.assertIn("[Content_Types].xml", self.screen)
        self.assertIn("xl/workbook.xml", self.screen)
        self.assertIn("xl/worksheets/sheet1.xml", self.screen)
        self.assertIn('rightToLeft="1"', self.screen)
        self.assertIn("inlineStr", self.screen)
        self.assertIn('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', self.screen)
        self.assertIn("Excel XLSX", self.screen)

    def test_invalid_date_never_expands_sales_to_all_history(self):
        self.assertIn("require(!dateError) { \"لا يمكن تصدير تقرير بفترة غير صالحة.\" }", self.screen)
        self.assertIn("require(!cloudMode || (!cloudLoading && selectedReportSourceError.isBlank()))", self.screen)
        self.assertIn("val selectedRows = if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) emptyList()", self.screen)
        self.assertIn("val inPeriodSales = if (dateError) emptyList()", self.screen)
        self.assertIn("val sourceErrorForReport: (String) -> String", self.screen)
        self.assertIn("val currencyTotal = if (dateError || (cloudMode && (cloudLoading || salesDocumentsLoadError.isNotBlank()))) emptyMap()", self.screen)
        self.assertIn("لا يمكن حساب الملخص: تحقق من الفترة المحددة", self.screen)

    def test_ledger_account_statement_requires_account_filter(self):
        self.assertIn('ReportCatalogItem("كشف حساب محاسبي"', self.screen)
        self.assertIn('selectedReport != "كشف حساب محاسبي" || (accountQuery.isNotBlank()', self.screen)
        self.assertIn('label = { Text("رمز الحساب أو اسمه") }', self.screen)

    def test_pdf_export_has_native_renderer(self):
        self.assertIn("writeReportPdf", self.screen)
        self.assertIn("application/pdf", self.screen)
        self.assertIn("Intent.ACTION_SEND", self.screen)
        self.assertIn("StaticLayout.Builder", self.screen)
        self.assertIn("TextDirectionHeuristics.RTL", self.screen)
        self.assertIn("PrintManager", self.screen)
        self.assertIn('Text("معاينة وطباعة")', self.screen)

    def test_customer_statement_uses_invoice_payments_returns_and_customer_filter(self):
        self.assertIn('"أرصدة العملاء وكشف الحساب" ->', self.screen)
        self.assertIn("doc.customerId.isNotBlank()", self.screen)
        self.assertIn('it.status.lowercase(Locale.ROOT) in setOf("succeeded", "partially_refunded", "refunded")', self.screen)
        self.assertIn('it.status.equals("completed", true)', self.screen)
        self.assertIn('it.refundStatus.equals("refunded", true)', self.screen)
        self.assertIn("الرصيد الختامي المحسوب من الحركات المتاحة", self.screen)

    def test_supplier_statement_uses_unique_posted_payment_rows_not_journal_totals(self):
        self.assertIn('"كشف حركات الموردين" ->', self.screen)
        self.assertIn("loadCommercialCloudSupplierPaymentMovements(context)", self.screen)
        start = self.repository.index("internal fun loadCommercialCloudSupplierPaymentMovements(")
        end = self.repository.index("\ninternal data class CommercialCloudPurchaseLine(", start)
        loader = self.repository[start:end]
        self.assertIn("commercial_supplier_payments", loader)
        self.assertIn("journal.status=eq.posted", loader)
        self.assertIn('val id: String,', loader)
        self.assertIn('if (id.isBlank() || obligationId.isBlank() || invoiceId.isBlank() || supplierId.isBlank() || journalId.isBlank()', loader)

    def test_inventory_movement_report_uses_scoped_source_and_preserves_fractional_quantity(self):
        self.assertIn('"حركة المخزون الفعلية" ->', self.screen)
        start = self.repository.index("internal fun loadCommercialCloudStockMovements(")
        end = self.repository.index("\ninternal data class CommercialCloudSupplierPaymentMovement(", start)
        loader = self.repository[start:end]
        self.assertIn("commercial_stock_movements", loader)
        self.assertIn("offering.activity.organization_id=eq.$orgId", loader)
        self.assertIn("quantity_delta", loader)
        self.assertIn("quantityDelta.stripTrailingZeros().toPlainString()", self.screen)
        self.assertIn("reportLineAmount(row, cloudMode)", self.screen)

    def test_report_filters_apply_to_rendered_and_exported_rows(self):
        self.assertIn("currencyFilter.isBlank() || row.currency.contains(currencyFilter.trim(), true)", self.screen)
        self.assertIn("statusFilter.isBlank() || row.status.contains(statusFilter.trim(), true)", self.screen)
        self.assertIn("rowFilter.isBlank() || row.name.contains(rowFilter.trim(), true)", self.screen)

    def test_posted_journal_detail_report_shows_debit_credit_and_source(self):
        self.assertIn('"تفاصيل القيود المرحلة" ->', self.screen)
        self.assertIn("loadCommercialCloudPostedJournalLines(context)", self.screen)
        self.assertIn("مدين: ${reportMoney(line.debitMinor, line.currencyCode, true)}", self.screen)
        self.assertIn("دائن: ${reportMoney(line.creditMinor, line.currencyCode, true)}", self.screen)
        self.assertIn("line.sourceType", self.screen)

    def test_financial_statements_require_balanced_posted_ledger_and_separate_currencies(self):
        self.assertIn('val ledgerStatementError', self.screen)
        self.assertIn('throughEnd.groupBy { it.entryId to it.currencyCode }', self.screen)
        self.assertIn('إجمالي الأصول', self.screen)
        self.assertIn('الأصول − الالتزامات − حقوق الملكية', self.screen)
        self.assertIn('ReportCatalogItem("قائمة الدخل والميزانية العمومية"', self.screen)
        self.assertIn('ReportCatalogItem("التدفقات النقدية والصندوق"', self.screen)

    def test_customer_and_supplier_statements_use_party_linked_posted_journal_lines(self):
        self.assertIn('loadCommercialCloudCustomerPartyAccounts(context)', self.screen)
        self.assertIn('loadCommercialCloudSupplierPartyAccounts(context)', self.screen)
        self.assertIn('partyAccountId = line.optString("party_account_id")', self.repository)
        self.assertIn('"الرصيد المحاسبي الكامل للعملاء" -> if (customerStatementError.isNotBlank()) emptyList()', self.screen)
        self.assertIn('"أرصدة الموردين والمستحقات" -> if (supplierStatementError.isNotBlank()) emptyList()', self.screen)

    def test_historical_cogs_uses_stored_cost_and_only_reverses_restocked_return_cost(self):
        self.assertIn('costTotalMinor = if (item.isNull("cost_total_minor")) null', self.repository)
        self.assertIn('saleItemCostTotalMinor = if (saleItem == null || saleItem.isNull("cost_total_minor")) null', self.repository)
        self.assertIn('item.restockedQuantity <= 0', self.screen)
        self.assertIn('تكلفة البنود المباعة المحفوظة', self.screen)
        self.assertIn('val costProfitError', self.screen)

    def test_cash_report_uses_mapped_cash_accounts_and_posted_ledger_only(self):
        self.assertIn('loadCommercialCloudGlAccountMappings(context)', self.screen)
        self.assertIn('val cashKeys = setOf("cash", "bank", "card_clearing", "other_receipts", "check_clearing", "other_disbursements")', self.screen)
        self.assertIn('رصيد افتتاحي', self.screen)
        self.assertIn('رصيد ختامي', self.screen)

    def test_customer_collection_idempotent_replay_requires_posted_journal(self):
        self.assertIn("customer_payment_journal_not_posted", self.collection_replay_migration)
        self.assertIn("e.source_type = 'payment'", self.collection_replay_migration)
        self.assertIn("e.source_id = v_existing.id", self.collection_replay_migration)
        self.assertIn("e.status = 'posted'", self.collection_replay_migration)
        self.assertIn("'idempotent_replay', true", self.collection_replay_migration)

    def test_inventory_value_uses_saved_unit_cost_and_mapped_currency(self):
        self.assertIn("unitCostMinor: Long?", self.repository)
        self.assertIn("quantityOnHand: java.math.BigDecimal", self.repository)
        self.assertIn('it.mappingKey == "inventory"', self.screen)
        self.assertIn("التقييم المالي الحالي للمخزون", self.screen)

    def test_cloud_stock_reports_use_inventory_balances_and_movements(self):
        self.assertIn("loadCommercialCloudStockAlerts(context)", self.screen)
        self.assertIn("loadCommercialCloudStockMovements(context)", self.screen)
        self.assertIn("cloudStockAlerts.filter { it.availableQuantity <= it.reorderPoint }", self.screen)
        self.assertIn("offering.activity.organization_id=eq.$orgId", self.repository)
        self.assertIn("commercial_stock_movements", self.repository)

    def test_csv_contains_period_source_timestamp_and_arabic_bom(self):
        self.assertIn('writer.write("\\uFEFF")', self.screen)
        self.assertIn('add(listOf("الفترة", startText + " — " + endText))', self.screen)
        self.assertIn('add(listOf("مصدر البيانات"', self.screen)
        self.assertIn('add(listOf("تاريخ التصدير"', self.screen)


if __name__ == "__main__":
    unittest.main()
