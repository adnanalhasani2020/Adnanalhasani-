from pathlib import Path
import unittest


class CommercialRlsInitplanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = Path("supabase/migrations/20261031030000_commercial_rls_auth_initplan.sql").read_text(encoding="utf-8")

    def test_policies_cache_auth_uid_without_widening_access(self):
        self.assertEqual(self.sql.count("(SELECT auth.uid())"), 13)
        self.assertNotIn("auth.uid())", self.sql.replace("(SELECT auth.uid())", ""))
        self.assertIn("ARRAY['owner','admin','accountant']::text[]", self.sql)
        self.assertIn("ARRAY['owner','admin','manager']::text[]", self.sql)
        self.assertIn("m.state='active'", self.sql)

    def test_policy_replacements_are_scoped_and_transactional(self):
        self.assertTrue(self.sql.startswith("-- Cache auth.uid()"))
        self.assertIn("BEGIN;", self.sql)
        self.assertIn("COMMIT;", self.sql)
        for table in (
            "commercial_expense_vouchers", "commercial_gl_account_mappings",
            "commercial_offering_sell_units", "commercial_purchase_receipt_items",
            "commercial_purchase_receipts", "commercial_supplier_invoice_items",
            "commercial_supplier_invoices", "commercial_supplier_obligations",
            "commercial_supplier_payments", "commercial_supplier_gl_accounts",
            "commercial_supplier_party_accounts",
        ):
            self.assertIn(f"ON public.{table}", self.sql)


if __name__ == "__main__":
    unittest.main()
