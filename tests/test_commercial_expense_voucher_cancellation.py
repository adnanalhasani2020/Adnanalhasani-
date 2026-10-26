from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026170000_commercial_expense_voucher_cancellation.sql"
AUDIT_MIGRATION = ROOT / "supabase/migrations/20261026190000_expense_voucher_cancellation_audit.sql"
EXPENSE_BASE = ROOT / "supabase/migrations/20261026121906_commercial_cloud_expense_vouchers.sql"
GL_REVERSE = ROOT / "supabase/migrations/20261024191246_general_ledger_sales_collections_idempotency_reversals.sql"


class CommercialExpenseVoucherCancellationTests(unittest.TestCase):
    def test_cancellation_preserves_source_and_records_linked_reversal(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        for token in (
            "canceled_at", "canceled_by", "cancellation_reason",
            "reversal_journal_entry_id", "ON DELETE RESTRICT",
            "public.commercial_gl_reverse_entry", "FOR UPDATE",
            "expense_voucher_reversal_not_confirmed", "expense_voucher_original_journal_not_posted",
        ):
            self.assertIn(token, sql)
        self.assertNotIn("DELETE FROM public.commercial_expense_vouchers", sql)

    def test_cancellation_is_authorized_and_retry_safe(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("m.state = 'active'", sql)
        self.assertIn("m.role IN ('owner','admin','manager','accountant')", sql)
        self.assertIn("expense_voucher_already_canceled_with_different_reason", sql)
        self.assertIn("'idempotent_replay', true", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_cancel_expense_voucher", sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_cancel_expense_voucher", sql)

    def test_source_voucher_and_gl_reversal_are_transactionally_coupled(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertLess(sql.index("public.commercial_gl_reverse_entry"), sql.index("UPDATE public.commercial_expense_vouchers"))
        self.assertIn("reversal_journal_entry_id = v_reversal_id", sql)
        self.assertIn("commercial_expense_vouchers_reversal_unique", sql)

    def test_cancellation_writes_one_append_only_audit_event_in_same_transaction(self):
        sql = AUDIT_MIGRATION.read_text(encoding="utf-8")
        self.assertIn("AFTER UPDATE OF canceled_at, canceled_by, cancellation_reason, reversal_journal_entry_id", sql)
        self.assertIn("OLD.canceled_at IS NULL AND NEW.canceled_at IS NOT NULL", sql)
        self.assertIn("'expense_voucher.cancelled'", sql)
        self.assertIn("'original_journal_entry_id', NEW.journal_entry_id", sql)
        self.assertIn("'reversal_journal_entry_id', NEW.reversal_journal_entry_id", sql)
        self.assertIn("'cancellation_reason', NEW.cancellation_reason", sql)
        self.assertIn("REVOKE ALL ON FUNCTION commercial_private.commercial_audit_expense_voucher_cancellation()", sql)


if __name__ == "__main__":
    unittest.main()
