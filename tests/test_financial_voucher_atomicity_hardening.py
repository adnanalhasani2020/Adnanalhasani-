from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REVERSAL_VERIFICATION = ROOT / "supabase/migrations/20261026180434_verify_expense_voucher_reversal_integrity.sql"
CANCELLATION = ROOT / "supabase/migrations/20261026170000_commercial_expense_voucher_cancellation.sql"
COLLECTION = ROOT / "supabase/migrations/20261024220000_commercial_customer_collection_rpc.sql"
EXPENSE = ROOT / "supabase/migrations/20261026121906_commercial_cloud_expense_vouchers.sql"
ANDROID_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class FinancialVoucherAtomicityHardeningTests(unittest.TestCase):
    def test_expense_cancellation_checks_persisted_reversal_lineage_and_posted_state(self):
        sql = REVERSAL_VERIFICATION.read_text(encoding="utf-8")
        for marker in (
            "FOR UPDATE",
            "reversal_of = v_voucher.journal_entry_id",
            "v_reversal_status IS DISTINCT FROM 'posted'",
            "expense_voucher_reversal_not_posted_or_linked",
            "expense_voucher_reversal_lines_missing",
            "expense_voucher_reversal_unbalanced",
            "expense_voucher_reversal_does_not_mirror_original",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, sql)

    def test_reversal_must_mirror_each_account_currency_and_party(self):
        sql = REVERSAL_VERIFICATION.read_text(encoding="utf-8")
        self.assertIn("FULL JOIN reversal_lines", sql)
        self.assertIn("r.party_account_id IS NOT DISTINCT FROM o.party_account_id", sql)
        self.assertIn("r.debit_minor IS DISTINCT FROM o.credit_minor", sql)
        self.assertIn("r.credit_minor IS DISTINCT FROM o.debit_minor", sql)
        self.assertIn("GROUP BY journal_entry_id, currency_code", sql)

    def test_failed_reversal_verification_prevents_voucher_cancellation_update(self):
        sql = REVERSAL_VERIFICATION.read_text(encoding="utf-8")
        self.assertLess(sql.index("expense_voucher_reversal_does_not_mirror_original"), sql.index("UPDATE public.commercial_expense_vouchers"))
        self.assertIn("SET canceled_at = now()", sql)
        self.assertIn("commercial_expense_vouchers_cancellation_state_check", CANCELLATION.read_text(encoding="utf-8"))

    def test_customer_collection_keeps_payment_and_gl_posting_in_one_rpc_transaction(self):
        sql = COLLECTION.read_text(encoding="utf-8")
        self.assertIn("pg_advisory_xact_lock", sql)
        self.assertIn("commercial_gl_post_payment(v_payment.id)", sql)
        self.assertIn("idempotent_replay", sql)
        self.assertIn("FOR UPDATE", sql)

    def test_expense_creation_keeps_voucher_and_posted_journal_in_one_rpc_transaction(self):
        sql = EXPENSE.read_text(encoding="utf-8")
        self.assertIn("pg_advisory_xact_lock", sql)
        self.assertIn("commercial_private.commercial_gl_post_entry", sql)
        self.assertIn("UPDATE public.commercial_expense_vouchers SET journal_entry_id=v_entry_id", sql)
        self.assertIn("expense_idempotency_key_reused_with_different_request", sql)

    def test_android_keeps_expense_and_collection_retry_keys_until_server_confirmation(self):
        repository = ANDROID_REPOSITORY.read_text(encoding="utf-8")
        expense_start = repository.index("internal fun recordCommercialCloudExpense(")
        expense_end = repository.index("internal fun recordCommercialCloudSupplierPayment(", expense_start)
        expense_block = repository[expense_start:expense_end]
        self.assertIn("p_idempotency_key", expense_block)
        collection_start = repository.index("internal fun recordCommercialCloudCustomerPayment(")
        collection_end = repository.index("/** Fetch the server-issued numbered receipt", collection_start)
        collection_block = repository[collection_start:collection_end]
        self.assertIn("commercial_cloud_pending_collection_v1", collection_block)
        self.assertIn("check(prefs.edit().clear().commit())", collection_block)


if __name__ == "__main__":
    unittest.main()
