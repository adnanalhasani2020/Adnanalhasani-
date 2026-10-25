"""Regression checks for refund GL posting order.

These are source-contract checks only; authenticated database integration tests
remain a release gate because the production GL trigger cannot be exercised here.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025173500_fix_return_refund_gl_settlement_order.sql"


class CommercialRefundMigrationContractTests(unittest.TestCase):
    def test_return_gl_routine_is_called_once_before_refund_transition(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertEqual(sql.count("public.commercial_gl_post_sale_return(r.id)"), 1)
        self.assertLess(
            sql.index("v_journal:=public.commercial_gl_post_sale_return(r.id);"),
            sql.index("SET refund_status='refunded'"),
        )

    def test_refund_transition_and_evidence_are_transactionally_recorded(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("p_evidence_reference", sql)
        self.assertIn("refund_status='refunded'", sql)
        self.assertIn("'sale_return.refund_executed'", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_execute_sale_return_refund", sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_execute_sale_return_refund", sql)


if __name__ == "__main__":
    unittest.main()
