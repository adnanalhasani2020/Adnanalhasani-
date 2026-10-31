from pathlib import Path
import unittest


class CommercialForeignKeyIndexesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = Path("supabase/migrations/20261031040000_commercial_foreign_key_indexes.sql").read_text(encoding="utf-8")

    def test_targets_all_advisor_foreign_key_findings(self):
        expected = ["commercial_expense_vouchers_journal_entry_id_fkey","commercial_expense_vouchers_reversal_journal_entry_id_fkey","commercial_inventory_receipts_created_by_fkey","commercial_inventory_receipts_location_id_fkey","commercial_inventory_receipts_offering_id_fkey","commercial_purchase_order_items_offering_id_fkey","commercial_purchase_orders_cancelled_by_fkey","commercial_purchase_orders_receive_location_id_fkey","commercial_purchase_receipt_i_organization_id_purchase_ord_fkey","commercial_purchase_receipt_i_organization_id_receipt_id_p_fkey","commercial_purchase_receipt_items_inventory_receipt_id_fkey","commercial_purchase_receipt_items_offering_id_fkey","commercial_purchase_receipt_items_organization_id_fkey","commercial_purchase_receipt_items_purchase_order_item_id_fkey","commercial_purchase_receipts_location_id_fkey","commercial_purchase_receipts_organization_id_purchase_orde_fkey","commercial_purchase_receipts_received_by_fkey","commercial_return_refund_postings_executed_by_fkey","commercial_return_restock_postings_created_by_fkey","commercial_return_restock_postings_location_id_fkey","commercial_return_restock_postings_offering_id_fkey","commercial_return_restock_postings_organization_id_fkey","commercial_return_restock_postings_return_id_fkey","commercial_supplier_gl_accoun_organization_id_ledger_accou_fkey","commercial_supplier_invoice_items_purchase_order_item_id_fkey","commercial_supplier_invoices_purchase_order_id_fkey","commercial_supplier_invoices_recorded_by_fkey","commercial_supplier_invoices_supplier_id_fkey","commercial_supplier_obligations_journal_entry_id_fkey","commercial_supplier_obligations_party_account_id_fkey","commercial_supplier_payment_obligation_identity_fk","commercial_supplier_payments_journal_entry_id_fkey","commercial_supplier_payments_obligation_id_fkey","commercial_supplier_payments_recorded_by_fkey","commercial_supplier_payments_supplier_invoice_id_fkey"]
        for constraint in expected:
            self.assertIn("'" + constraint + "'", self.sql)
        self.assertEqual(len(expected), 35)

    def test_composite_fk_column_order_is_preserved_dynamically(self):
        self.assertIn("unnest((SELECT conkey FROM pg_constraint WHERE oid=v_constraint.oid))", self.sql)
        self.assertIn("ORDER BY k.ordinality", self.sql)
        self.assertIn("CREATE INDEX IF NOT EXISTS", self.sql)

    def test_migration_fails_closed_if_schema_drifted(self):
        self.assertIn("IF v_found <> cardinality(v_constraint_names)", self.sql)
        self.assertIn("RAISE EXCEPTION 'Expected % foreign keys for indexing'", self.sql)


if __name__ == "__main__":
    unittest.main()
