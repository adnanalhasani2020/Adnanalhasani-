from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
EXPENSE_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudExpensesScreen.kt"
EXPENSE_INVARIANT = ROOT / "supabase/migrations/20261026210000_expense_voucher_commit_integrity.sql"
COLLECTION_GUARD = ROOT / "supabase/migrations/20261026213000_customer_collection_respects_returns.sql"


class ReceivablesAndExpenseIntegrityTests(unittest.TestCase):
    def test_receivables_fetch_finalized_returns_and_subtract_approved_credit_once(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        start = source.index("internal fun loadCommercialCloudReceivables(")
        end = source.index("\ninternal fun recordCommercialCloudCustomerPayment(", start)
        block = source[start:end]
        self.assertIn("returns:commercial_sales_returns(id,status,refund_status,approved_refund_minor)", block)
        self.assertIn('saleReturn.optString("status") == "completed"', block)
        self.assertIn("creditedMinor = credited", block)
        self.assertIn('saleReturn.optString("refund_status") == "refunded"', block)
        self.assertNotIn("refund_postings:commercial_return_refund_postings(amount_minor)", block)
        self.assertIn("val netPaid = (paid - refunded).coerceAtLeast(0L)", block)
        self.assertIn("val due = (total - netPaid - credited).coerceAtLeast(0L)", block)
        self.assertIn("The refund RPC inserts the internal refund posting", block)

    def test_customer_collection_return_guard_keeps_definer_logic_private(self):
        migration = COLLECTION_GUARD.read_text(encoding="utf-8")
        self.assertIn("CREATE OR REPLACE FUNCTION commercial_private.commercial_record_customer_payment(", migration)
        self.assertNotIn("CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment(", migration)
        self.assertIn("SECURITY DEFINER", migration)
        self.assertIn("REVOKE ALL ON FUNCTION commercial_private.commercial_record_customer_payment", migration)
        self.assertIn("GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_customer_payment", migration)
        self.assertNotIn("GRANT EXECUTE ON FUNCTION public.commercial_record_customer_payment", migration)
        self.assertIn("collection_exceeds_outstanding_balance_after_returns", migration)
        self.assertIn("v_outstanding_minor := GREATEST(", migration)
        self.assertIn("commercial_payment_gl_post", migration)
        self.assertIn("customer_payment_journal_not_posted", migration)
        self.assertIn("e.source_id = v_payment.id", migration)
        self.assertNotIn("v_posting := public.commercial_gl_post_payment(v_payment.id)", migration)
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_gl_post_payment", migration)
        self.assertIn("payment_exceeds_outstanding_after_returns", migration)
        self.assertIn("pay.status IN ('succeeded','partially_refunded','refunded')", migration)
        self.assertIn("pay.id<>p.id", migration)
        self.assertNotIn("payment_exceeds_sale_total", migration)
        self.assertIn("v_outstanding:=GREATEST(", migration)

    def test_inventory_receipt_and_return_restock_rls_scope_to_row_organization(self):
        migration = (ROOT / "supabase/migrations/20261026213200_fix_inventory_receipt_and_return_restock_rls.sql").read_text(encoding="utf-8")
        self.assertIn("m.organization_id = commercial_inventory_receipts.organization_id", migration)
        self.assertIn("m.organization_id = commercial_return_restock_postings.organization_id", migration)
        self.assertEqual(migration.count("m.user_id = (SELECT auth.uid())"), 2)
        self.assertNotIn("m.organization_id = m.organization_id", migration)
        self.assertEqual(migration.count("CREATE POLICY"), 2)

    def test_customer_party_account_rls_migration_scopes_member_and_admin_policies_to_row_tenant(self):
        migration = (ROOT / "supabase/migrations/20261026213100_fix_customer_party_accounts_rls_scope.sql").read_text(encoding="utf-8")
        self.assertIn("m.organization_id = commercial_customer_party_accounts.organization_id", migration)
        self.assertIn("m.user_id = (SELECT auth.uid())", migration)
        self.assertIn("m.role IN ('owner', 'admin', 'manager')", migration)
        self.assertIn("WITH CHECK", migration)
        self.assertNotIn("m.organization_id = m.organization_id", migration)
        self.assertIn("ENABLE ROW LEVEL SECURITY", migration)

    def test_daily_expense_total_uses_organization_timezone_not_device_date(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        screen = EXPENSE_SCREEN.read_text(encoding="utf-8")
        self.assertIn("loadCommercialCloudExpenseReportingTimeZone", repository)
        self.assertIn("default_timezone", repository)
        self.assertIn("LocalDate.now(reportingTimeZone)", screen)
        self.assertIn(".atZone(reportingTimeZone).toLocalDate()", screen)
        self.assertNotIn('SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())', screen)

    def test_receivable_model_subtracts_executed_refunds_and_approved_return_credit(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        start = source.index("data class CommercialCloudReceivable(")
        end = source.index("\ninternal fun loadCommercialCloudReceivables(", start)
        model = source[start:end]
        self.assertIn("val refundedMinor: Long", model)
        self.assertIn("val netPaidMinor: Long get() = (paidMinor - refundedMinor).coerceAtLeast(0L)", model)
        self.assertIn("val dueMinor: Long get() = (totalMinor - netPaidMinor - creditedMinor).coerceAtLeast(0L)", model)
        self.assertIn("refundedMinor = refunded", source)

    def test_receivables_screen_labels_net_paid_and_return_adjustments(self):
        screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
        self.assertIn("row.netPaidMinor", screen)
        self.assertIn("row.refundedMinor", screen)
        self.assertIn("row.creditedMinor", screen)
        self.assertIn("صافي المحصّل بعد الاسترداد", screen)
        self.assertIn("خصم المرتجعات المعتمدة", screen)

    def test_customer_collection_server_guard_accounts_for_credits_and_executed_refunds(self):
        sql = COLLECTION_GUARD.read_text(encoding="utf-8")
        self.assertIn("v_refunded_minor", sql)
        self.assertIn("v_credited_minor", sql)
        self.assertIn("commercial_return_refund_postings", sql)
        self.assertIn("sr.status = 'completed'", sql)
        self.assertIn("collection_exceeds_outstanding_balance_after_returns", sql)
        self.assertLess(sql.index("collection_exceeds_outstanding_balance_after_returns"), sql.index("INSERT INTO public.commercial_payments("))

    def test_expense_voucher_commit_guard_matches_source_amount_and_currency(self):
        sql = (ROOT / "supabase/migrations/20261026213300_expense_voucher_source_amount_integrity.sql").read_text(encoding="utf-8")
        for marker in (
            "expense_voucher_journal_source_mismatch_at_commit",
            "expense_voucher_journal_currency_mismatch_at_commit",
            "expense_voucher_journal_amount_mismatch_at_commit",
            "v_journal_source_type IS DISTINCT FROM 'expense_voucher'",
            "v_journal_source_id IS DISTINCT FROM v_voucher.id",
            "v_debit_total <> v_voucher.amount_minor",
            "v_credit_total <> v_voucher.amount_minor",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, sql)

    def test_expense_voucher_cannot_commit_without_posted_balanced_journal(self):
        sql = EXPENSE_INVARIANT.read_text(encoding="utf-8")
        for marker in (
            "DEFERRABLE INITIALLY DEFERRED",
            "expense_voucher_journal_link_required_at_commit",
            "expense_voucher_journal_must_be_posted_at_commit",
            "expense_voucher_journal_lines_missing_at_commit",
            "expense_voucher_journal_unbalanced_at_commit",
            "commercial_expense_voucher_posted_integrity",
            "GROUP BY currency_code",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, sql)


    def test_customer_and_expense_queries_do_not_predefine_pagination_limits(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        customers_start = source.index("internal fun loadCommercialCloudCustomers(")
        customers_end = source.index("\ninternal fun ", customers_start + 1)
        customers_block = source[customers_start:customers_end]
        expenses_start = source.index("internal fun loadCommercialCloudExpenses(")
        expenses_end = source.index("\ninternal fun ", expenses_start + 1)
        expenses_block = source[expenses_start:expenses_end]
        self.assertIn("commercialCloudPagedGet(path, access)", customers_block)
        self.assertNotIn("limit=", customers_block)
        self.assertIn("commercialCloudPagedGet(path, access)", expenses_block)
        self.assertNotIn("limit=", expenses_block)

        paging_start = source.index("private fun commercialCloudPagedGet(")
        paging_end = source.index("\ninternal fun ", paging_start + 1)
        paging_block = source[paging_start:paging_end]
        self.assertIn('!path.contains("limit=")', paging_block)
        self.assertIn('!path.contains("offset=")', paging_block)

if __name__ == "__main__":
    unittest.main()
