from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt"
PURCHASES = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"
COMPOSER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSaleComposerScreen.kt"
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"


class CommercialCloudSalesScreenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.purchases = PURCHASES.read_text(encoding="utf-8")
        cls.composer = COMPOSER.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")

    def test_invoice_projection_uses_existing_tables_and_columns(self):
        self.assertIn("internal fun loadCommercialCloudSaleDocuments", self.repository)
        for field in (
            "receipt_number", "total_minor", "payment_status", "currency_code",
            "commercial_customers(display_name)",
            "commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor)",
        ):
            with self.subTest(field=field):
                self.assertIn(field, self.repository)

    def test_multiline_checkout_links_customer_atomically_and_scopes_validation(self):
        migration = (ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql").read_text()
        self.assertIn("p_customer_id uuid DEFAULT NULL", migration)
        self.assertIn("c.id = v_customer AND c.organization_id = v_org AND c.state = 'active'", migration)
        self.assertIn("organization_id,customer_id,idempotency_key", migration)
        self.assertIn("'customer_id', p_customer_id", migration)
        self.assertIn("internal fun loadCommercialCloudCustomers(context: Context)", self.repository)
        self.assertIn("loadCommercialCloudCustomers(context)", self.composer)
        self.assertIn("p_customer_id", self.repository)
        self.assertIn("ربط الفاتورة بالعميل", self.composer)
        self.assertIn("checkoutCommercialCloudSaleMulti(context, requestLines, key, customerId,", self.composer)
        self.assertIn("paymentModeForRequest", self.composer)
        self.assertIn("initialPaymentMinor", self.composer)

    def test_credit_checkout_requires_customer_and_tracks_outstanding(self):
        migration = (ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql").read_text()
        self.assertIn("p_payment_mode text DEFAULT 'cash'", migration)
        self.assertIn("customer_required_for_credit_sale", migration)
        self.assertIn("initial_payment_exceeds_invoice_total", migration)
        self.assertIn("'outstanding_minor',(v_total-v_initial_payment)::bigint", migration)
        self.assertIn("p_payment_mode", self.repository)
        self.assertIn("آجل / دين", self.composer)
        self.assertIn("اختر العميل قبل تسجيل البيع الآجل", self.composer)

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

    def test_sales_tab_links_to_detailed_cloud_invoice_history(self):
        self.assertIn("onCloudInvoices = { cloudSalesPage = true }", self.main)
        self.assertIn("عرض تفاصيل الفواتير السحابية", self.main)
        self.assertIn('if (cloudMode) "ملخص الفواتير المحمّلة من قاعدة البيانات"', self.main)

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


    def test_invoice_list_supports_expandable_details_and_invoice_id_search(self):
        self.assertIn("expandedInvoiceIds", self.screen)
        self.assertIn("عرض التفاصيل", self.screen)
        self.assertIn("doc.id.contains(search, true)", self.screen)
        self.assertIn("معرّف الفاتورة:", self.screen)

    def test_invoice_details_show_unit_price_line_total_and_formatted_timestamp(self):
        for marker in ("سعر الوحدة:", "الإجمالي:", "formatInvoiceTimestamp", "formatMinorAmount"):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.screen)

    def test_invoice_timestamps_parse_iso8601_offsets_before_local_display(self):
        self.assertIn("java.time.Instant.parse(value)", self.screen)
        self.assertIn("java.time.ZoneId.systemDefault()", self.screen)
        self.assertIn("explicit UTC offset", self.screen)
        self.assertIn("yyyy-MM-dd HH:mm:ss", self.screen)  # legacy fallback remains supported

    def test_invoice_screen_discloses_read_only_and_minor_units(self):
        self.assertIn("عرض فقط", self.screen)
        self.assertIn("بوحدة الصرف الصغرى", self.screen)
        self.assertIn("إعادة المحاولة", self.screen)
        self.assertIn("بنود الفاتورة", self.screen)

    def test_invoice_status_uses_sales_status_and_payment_status_separately(self):
        # Validate projection fields independently; query parameter order is not a contract.
        for field in ("payment_status", "status", "created_at"):
            with self.subTest(field=field):
                self.assertIn(field, self.repository)
        self.assertIn('saleStatus = row.optString("status")', self.repository)
        self.assertIn('saleStatus in setOf("cancelled", "canceled")', self.screen)
        self.assertIn('"refunded" to "مستردة"', self.screen)
        self.assertIn('"refunded", "partially_refunded"', self.screen)


    def test_sales_summary_uses_confirmed_cloud_payments_for_partial_balances(self):
        sales_projection = self.repository.split(
            "internal fun loadCommercialCloudSales", 1
        )[1].split("internal data class CommercialMasterDataRow", 1)[0]
        self.assertIn("payments:commercial_payments(amount_minor,status)", sales_projection)
        self.assertIn('it.optString("status").equals("succeeded", ignoreCase = true)', sales_projection)
        self.assertIn("paid = paidMinor.coerceAtMost(total)", sales_projection)
        self.assertIn('saleStatus == "completed"', sales_projection)
        self.assertIn("total > paidMinor", sales_projection)


    def test_cloud_customer_debts_use_server_projection_and_collection_rpc(self):
        self.assertIn("CommercialCloudDebtScreen(context = context", self.main)
        self.assertIn("showDebts = true", self.main)
        self.assertIn("internal fun loadCommercialCloudReceivables", self.repository)
        self.assertIn("payments:commercial_payments(amount_minor,status)", self.repository)
        self.assertIn('payment.optString("status") == "succeeded"', self.repository)
        self.assertIn("internal fun recordCommercialCloudCustomerPayment", self.repository)
        self.assertIn("/rest/v1/rpc/commercial_record_customer_payment", self.repository)
        self.assertIn("getSharedPreferences(\"commercial_cloud_pending_collection_v1\"", self.repository)

    def test_customer_collection_migration_is_atomic_authorized_and_idempotent(self):
        migration = (ROOT / "supabase/migrations/20261024220000_commercial_customer_collection_rpc.sql").read_text(encoding="utf-8")
        for marker in (
            "CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment",
            "pg_advisory_xact_lock",
            "idempotency_key_reused_with_different_request",
            "accounting_permission_required",
            "commercial_gl_post_payment(v_payment.id)",
            "GRANT EXECUTE ON FUNCTION public.commercial_record_customer_payment",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, migration)

    def test_saved_invoice_has_print_and_share_actions_with_escaped_rtl_rendering(self):
        screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt").read_text(encoding="utf-8")
        for marker in (
            "shareSavedInvoice(context, document, confirmedPaidMinor)",
            "printSavedInvoice(context, document, confirmedPaidMinor)",
            "Intent.ACTION_SEND",
            "PrintManager",
            'dir="rtl"',
            'replace("<", "&lt;")',
            "معرّف السجل المحفوظ",
            "ليست إقرارًا بالامتثال لمتطلبات فاتورة ضريبية محلية",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, screen)

    def test_multi_line_checkout_guards_duplicate_lines_and_uses_stable_retry_key(self):
        self.assertIn("commercial_checkout_sale_multi", self.repository)
        self.assertIn("getOrCreateCommercialCloudMultiSaleKey", self.repository)
        self.assertIn("aggregateCommercialCheckoutLines(lines)", self.repository)
        self.assertIn("Math.addExact(totals[line.offeringId] ?: 0, line.quantity)", self.repository)
        self.assertIn("prefs.getString(\"payload\", null) == payload", self.repository)
        migration = (ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql").read_text(encoding="utf-8")
        self.assertIn("FOR UPDATE", migration)
        self.assertIn("p_idempotency_key", migration)
        self.assertIn("commercial_inventory_balances", migration)

if __name__ == "__main__":
    unittest.main()
