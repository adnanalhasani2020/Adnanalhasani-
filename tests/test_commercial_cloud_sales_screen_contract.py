from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt"
PURCHASES = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
CASH_PAYMENT_MIGRATION = ROOT / "supabase/migrations/20261024193000_commercial_checkout_cash_payment_atomic.sql"
MULTILINE_CHECKOUT_MIGRATION = ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql"


class CommercialCloudSalesScreenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.purchases = PURCHASES.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.cash_payment_migration = CASH_PAYMENT_MIGRATION.read_text(encoding="utf-8")
        cls.multiline_checkout_migration = MULTILINE_CHECKOUT_MIGRATION.read_text(encoding="utf-8")

    def test_invoice_projection_uses_existing_tables_and_columns(self):
        self.assertIn("internal fun loadCommercialCloudSaleDocuments", self.repository)
        for field in (
            "receipt_number", "total_minor", "status,payment_status", "currency_code",
            "commercial_customers(display_name,phone,email)",
            "commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor)",
            "commercial_payments(amount_minor,status,payment_method_type,created_at)",
            "commercial_sales_returns(status,refund_status,restock_status,requested_refund_minor,approved_refund_minor,created_at)",
        ):
            with self.subTest(field=field):
                self.assertIn(field, self.repository)

    def test_invoice_projection_is_scoped_to_current_organization_and_activity(self):
        projection = self.repository.split(
            "internal fun loadCommercialCloudSaleDocuments", 1
        )[1].split("internal fun loadCommercialCloudSales", 1)[0]
        self.assertIn("organization_id=eq.$orgId", projection)
        self.assertIn("activity_id=eq.$activityId", projection)
        self.assertIn('commercialCloudPagedGet(path, access)', projection)
        self.assertIn('organization_id=eq.$orgId&activity_id=eq.$activityId', projection)
        self.assertNotIn('"/rest/v1/commercial_sales", "POST"', projection)

    def test_invoice_screen_is_reachable_from_more_menu(self):
        self.assertIn('"فواتير المبيعات السحابية" -> onCloudSales()', self.main)
        self.assertIn("cloudSalesPage -> CommercialCloudSalesScreen", self.main)
        self.assertIn('onCloudSales = { cloudSalesPage = true }', self.main)

    def test_purchase_order_projection_uses_existing_org_scoped_tables(self):
        self.assertIn("loadCommercialCloudPurchaseDocuments", self.repository)
        for field in ("commercial_purchase_order_items(", "commercial_suppliers(display_name)", "quantity_ordered", "quantity_received", "unit_cost_minor", "organization_id=eq.$orgId"):
            with self.subTest(field=field):
                self.assertIn(field, self.repository)
        self.assertIn("cloudPurchasesPage -> CommercialCloudPurchasesScreen", self.main)
        self.assertIn('"أوامر الشراء" -> onCloudPurchases()', self.main)

    def test_purchase_screen_discloses_read_only_receiving_boundary(self):
        self.assertIn("عرض فقط", self.purchases)
        self.assertIn("لم يتم تفعيل إنشاء الطلب أو اعتماد الاستلام", self.purchases)
        self.assertIn("إعادة المحاولة", self.purchases)

    def test_invoice_screen_supports_status_filters_and_currency_safe_totals(self):
        for marker in ('selectedStatus', '"paid" to "مدفوعة"', '"partial" to "مدفوعة جزئيًا"', '"due" to "مستحقة"', 'groupBy { it.currencyCode', 'بوحدة الصرف الصغرى'):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.screen)

    def test_invoice_details_show_unit_price_line_total_and_formatted_timestamp(self):
        for marker in ("سعر الوحدة:", "الإجمالي:", "formatInvoiceTimestamp", "formatMinorAmount",
                       "document.customerPhone", "document.customerEmail", "document.payments",
                       "document.returns", "document.saleStatus", "confirmedPaidMinor",
                       "المدفوعات الناجحة فقط", "المتبقي قبل احتساب المرتجعات"):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.screen)

    def test_invoice_screen_discloses_read_only_and_minor_units(self):
        self.assertIn("عرض فقط", self.screen)
        self.assertIn("بوحدة الصرف الصغرى", self.screen)
        self.assertIn("إعادة المحاولة", self.screen)
        self.assertIn("بنود الفاتورة", self.screen)


    def test_cloud_sale_refresh_failure_does_not_create_fake_local_invoice(self):
        cloud_sale_flow = self.main.split("if (newSale && inventory.isNotEmpty()) SaleEntryDialog", 1)[1].split(
            "if (settlingSaleNumber", 1
        )[0]
        cloud_checkout = cloud_sale_flow.split("            } else {\n                inventory[productIndex]", 1)[0]
        self.assertIn("لم يتم إنشاء سجل محلي بديل", cloud_checkout)
        self.assertNotIn("saveSales(context, sales)", cloud_checkout)
        self.assertIn("sales.addAll(serverSales)", cloud_checkout)

    def test_cloud_mode_blocks_local_only_settlements_and_finance_screens(self):
        self.assertIn("if (settlingSaleNumber >= 0 && cloudMode)", self.main)
        self.assertIn("تسجيل التحصيل السحابي غير مفعّل بعد", self.main)
        self.assertIn("المديونيات والتحصيلات السحابية لم تُربط بعد", self.main)
        self.assertIn("المصروفات السحابية لم تُربط بدفتر الحسابات بعد", self.main)

    def test_sales_tab_links_to_cloud_invoice_details(self):
        self.assertIn("onCloudInvoices = { cloudSalesPage = true }", self.main)
        self.assertIn("عرض تفاصيل الفواتير السحابية", self.main)
    def test_invoice_payment_state_requires_confirmed_payment_records(self):
        self.assertIn("successfulPaymentMinor = payments", self.repository)
        self.assertIn('it.status.equals("succeeded", ignoreCase = true)', self.repository)
        self.assertIn('successfulPaymentMinor >= totalMinor -> "paid"', self.repository)
        self.assertIn('successfulPaymentMinor > 0L -> "partially_paid"', self.repository)
        self.assertIn('else -> "pending"', self.repository)


    def test_cash_checkout_creates_one_idempotent_payment_in_same_transaction(self):
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(", self.cash_payment_migration)
        self.assertIn("CREATE OR REPLACE FUNCTION commercial_private.commercial_checkout_sale_with_cash_payment(", self.cash_payment_migration)
        self.assertIn("SECURITY INVOKER", self.cash_payment_migration)
        self.assertIn("SECURITY DEFINER", self.cash_payment_migration)
        self.assertIn("commercial_private.commercial_checkout_sale_with_cash_payment(", self.cash_payment_migration)
        self.assertIn("INSERT INTO public.commercial_payments", self.cash_payment_migration)
        self.assertNotIn("INSERT INTO public.commercial_payments", self.cash_payment_migration.split("CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(", 1)[1])
        self.assertIn("'sale:' || v_sale_id::text || ':cash'", self.cash_payment_migration)
        self.assertIn("ON CONFLICT (organization_id, idempotency_key) DO NOTHING", self.cash_payment_migration)
        self.assertIn("'succeeded', 'cash'", self.cash_payment_migration)
        self.assertIn("RETURN v_result || jsonb_build_object(", self.cash_payment_migration)
        self.assertIn("'payment_recorded', v_total > 0", self.cash_payment_migration)
        self.assertIn('totalMinor == 0L && paymentStatusFromServer.equals("paid", ignoreCase = true) -> "paid"', self.repository)
        self.assertIn("commercial_private.commercial_checkout_sale(", self.cash_payment_migration)


    def test_multiline_checkout_is_single_transactional_rpc_with_stock_and_payment(self):
        sql = self.multiline_checkout_migration
        self.assertIn("commercial_private.commercial_checkout_sale_multi(", sql)
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(", sql)
        self.assertIn("SECURITY DEFINER", sql)
        self.assertIn("SECURITY INVOKER", sql)
        self.assertIn("pg_advisory_xact_lock", sql)
        self.assertIn("FOR UPDATE OF b", sql)
        self.assertIn("INSERT INTO public.commercial_sales(", sql)
        self.assertIn("INSERT INTO public.commercial_sale_items(", sql)
        self.assertIn("INSERT INTO public.commercial_stock_movements(", sql)
        self.assertIn("INSERT INTO public.commercial_payments(", sql)
        self.assertIn("ON CONFLICT(organization_id,idempotency_key) DO NOTHING", sql)
        self.assertIn("mixed_currency_invoice_not_supported", sql)
        self.assertIn("insufficient_available_stock", sql)
        self.assertIn("duplicate_offering_lines_not_allowed", sql)

if __name__ == "__main__":
    unittest.main()
