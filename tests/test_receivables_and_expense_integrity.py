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
        self.assertIn("refund_postings:commercial_return_refund_postings(amount_minor)", block)
        self.assertIn("val netPaid = (paid - refunded).coerceAtLeast(0L)", block)
        self.assertIn("(totalMinor - paidMinor - creditedMinor).coerceAtLeast(0L)", block)
        self.assertIn("refund is not a second", block)

    def test_daily_expense_total_uses_organization_timezone_not_device_date(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        screen = EXPENSE_SCREEN.read_text(encoding="utf-8")
        self.assertIn("loadCommercialCloudExpenseReportingTimeZone", repository)
        self.assertIn("default_timezone", repository)
        self.assertIn("LocalDate.now(reportingTimeZone)", screen)
        self.assertIn(".atZone(reportingTimeZone).toLocalDate()", screen)
        self.assertNotIn('SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())', screen)

    def test_customer_collection_server_guard_accounts_for_credits_and_executed_refunds(self):
        sql = COLLECTION_GUARD.read_text(encoding="utf-8")
        self.assertIn("v_refunded_minor", sql)
        self.assertIn("v_credited_minor", sql)
        self.assertIn("commercial_return_refund_postings", sql)
        self.assertIn("sr.status = 'completed'", sql)
        self.assertIn("collection_exceeds_outstanding_balance_after_returns", sql)
        self.assertLess(sql.index("collection_exceeds_outstanding_balance_after_returns"), sql.index("INSERT INTO public.commercial_payments("))

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


if __name__ == "__main__":
    unittest.main()
