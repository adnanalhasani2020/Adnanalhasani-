from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026150000_commercial_cloud_expense_vouchers.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudExpensesScreen.kt"
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"


class CommercialCloudExpenseVoucherTests(unittest.TestCase):
    def test_expense_rpc_posts_balanced_gl_and_is_idempotent(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_private.commercial_gl_post_entry(", sql)
        self.assertIn("'expense_voucher'", sql)
        self.assertIn("expense_idempotency_key_reused_with_different_request", sql)
        self.assertIn("expense_cash_or_bank_account_not_configured_for_currency", sql)
        self.assertIn("p_amount_minor<=0", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_record_expense", sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_record_expense", sql)

    def test_customer_collection_rpc_is_restored_and_posts_once_via_trigger(self):
        restore = (ROOT / "supabase/migrations/20261026160000_restore_customer_collection_rpc.sql").read_text(encoding="utf-8")
        fix = (ROOT / "supabase/migrations/20261026161000_fix_customer_collection_double_gl_post.sql").read_text(encoding="utf-8")
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment", restore)
        self.assertIn("sale_return_reconciliation_required", restore)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_record_customer_payment", fix)
        self.assertIn("customer_payment_journal_not_posted", fix)
        self.assertIn("source_type='payment' AND source_id=v_payment.id", fix)
        self.assertNotIn("commercial_gl_post_payment(v_payment.id)", fix)

    def test_customer_collection_rechecks_key_under_organization_lock(self):
        fix = (ROOT / "supabase/migrations/20261026161000_fix_customer_collection_double_gl_post.sql").read_text(encoding="utf-8")
        hardening = (ROOT / "supabase/migrations/20261026162000_harden_customer_collection_idempotency.sql").read_text(encoding="utf-8")
        self.assertIn("customer_payment_journal_not_posted", fix)
        self.assertNotIn("commercial_gl_post_payment(v_payment.id)", fix)
        self.assertIn("customer-payment:'||btrim(p_idempotency_key)", hardening)
        self.assertIn("then recheck idempotency", hardening)
        self.assertIn("sale_return_reconciliation_required", hardening)

    def test_voucher_reads_are_tenant_and_role_scoped(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("ENABLE ROW LEVEL SECURITY", sql)
        self.assertIn("commercial_expense_vouchers_finance_read", sql)
        self.assertIn("m.organization_id=commercial_expense_vouchers.organization_id", sql)
        role_fix = (ROOT / "supabase/migrations/20261026163000_fix_expense_role_scope.sql").read_text(encoding="utf-8")
        self.assertIn("m.role IN ('owner','admin','manager')", role_fix)
        self.assertIn("REVOKE ALL ON TABLE public.commercial_expense_vouchers FROM PUBLIC, anon, authenticated", sql)

    def test_financial_rpc_wrappers_are_invokers_and_private_logic_is_guarded(self):
        migration = (ROOT / "supabase/migrations/20261026168000_move_financial_rpc_logic_private.sql").read_text(encoding="utf-8")
        self.assertIn("public.commercial_record_expense", migration)
        self.assertIn("public.commercial_record_customer_payment", migration)
        self.assertIn("LANGUAGE sql SECURITY INVOKER", migration)
        self.assertIn("commercial_private.commercial_record_expense", migration)
        self.assertIn("commercial_private.commercial_record_customer_payment", migration)
        self.assertIn("GRANT USAGE ON SCHEMA commercial_private TO authenticated", migration)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_record_expense", migration)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_record_customer_payment", migration)

    def test_customer_receipts_post_to_method_specific_gl_accounts(self):
        migration = (ROOT / "supabase/migrations/20261026167000_map_receipts_by_payment_method.sql").read_text(encoding="utf-8")
        self.assertIn("WHEN 'cash' THEN 'cash'", migration)
        self.assertIn("WHEN 'bank_transfer' THEN 'bank'", migration)
        self.assertIn("WHEN 'card' THEN 'card_clearing'", migration)
        self.assertIn("WHEN 'other' THEN 'other_receipts'", migration)
        self.assertIn("receipt_and_ar_mappings_required", migration)
        self.assertIn("CARDCLR_", migration)
        self.assertIn("OTHERREC_", migration)

    def test_gl_mapping_keys_cover_bank_discount_tax_and_validate_account_types(self):
        mapping_migration = (ROOT / "supabase/migrations/20261026165000_expand_gl_mapping_keys_and_seed_operating_accounts.sql").read_text(encoding="utf-8")
        validation = (ROOT / "supabase/migrations/20261026166000_validate_gl_mapping_account_types.sql").read_text(encoding="utf-8")
        for key in ("'bank'", "'sales_discounts'", "'sales_tax_payable'"):
            self.assertIn(key, mapping_migration)
        self.assertIn("a.account_type=x.account_type", validation)
        self.assertIn("a.normal_balance=x.normal_balance", validation)
        self.assertIn("commercial_initialize_gl_chart", validation)

    def test_internal_refund_postings_remain_unavailable_to_clients(self):
        migration = (ROOT / "supabase/migrations/20261026164000_lock_down_return_refund_postings.sql").read_text(encoding="utf-8")
        self.assertIn("REVOKE ALL ON TABLE public.commercial_return_refund_postings FROM PUBLIC, anon, authenticated", migration)
        self.assertIn("FOR ALL TO anon, authenticated", migration)
        self.assertIn("USING (false)", migration)
        self.assertIn("WITH CHECK (false)", migration)

    def test_expense_permissions_match_supported_organization_roles(self):
        migration = (ROOT / "supabase/migrations/20261026163000_fix_expense_role_scope.sql").read_text(encoding="utf-8")
        self.assertIn("m.role IN ('owner','admin','manager')", migration)
        self.assertNotIn("'accountant'", migration)

    def test_android_expense_amounts_use_currency_minor_units(self):
        screen = SCREEN.read_text(encoding="utf-8")
        self.assertIn("Currency.getInstance(code).defaultFractionDigits", screen)
        self.assertIn("movePointRight(cloudExpenseCurrencyScale(currency))", screen)
        self.assertIn("longValueExact()", screen)
        self.assertIn("formatCloudExpenseMinor(voucher.amountMinor, voucher.currencyCode)", screen)

    def test_android_expense_screen_uses_cloud_rpc_and_no_local_fallback(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        screen = SCREEN.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("/rest/v1/rpc/commercial_record_expense", repository)
        self.assertIn("loadCommercialCloudExpenseCurrencyCode", repository)
        self.assertIn("commercial_organizations?select=default_currency_code", repository)
        self.assertIn("commercial_expense_vouchers", repository)
        self.assertIn("recordCommercialCloudExpense", screen)
        self.assertIn("CommercialCloudExpensesScreen(context = context", main)


if __name__ == "__main__":
    unittest.main()
