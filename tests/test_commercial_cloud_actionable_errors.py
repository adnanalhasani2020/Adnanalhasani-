from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SALES_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt"
DEBT_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt"


class CommercialCloudActionableErrorTests(unittest.TestCase):
    def test_missing_rpc_schema_cache_error_is_actionable_and_warns_against_new_request(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn('code == "PGRST202"', repository)
        self.assertIn("دالة قاعدة البيانات المطلوبة غير منشورة", repository)
        self.assertIn("لا تنشئ عملية بديلة بمفتاح جديد", repository)

    def test_ambiguous_network_failure_keeps_retry_safe(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("انتهت مهلة الاتصال ولم يتمكن التطبيق من تأكيد النتيجة", repository)
        self.assertIn("لا تبدأ عملية جديدة بمفتاح مختلف", repository)
        self.assertIn("انقطع الاتصال قبل تأكيد النتيجة من الخادم", repository)

    def test_overpayment_and_stock_race_have_specific_user_messages(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("collection_exceeds_outstanding_balance", repository)
        self.assertIn("رُفض التحصيل لأن المبلغ يتجاوز الرصيد المستحق", repository)
        self.assertIn("insufficient_inventory", repository)
        self.assertIn("حدّث المخزون قبل المحاولة مجددًا", repository)

    def test_sales_and_debt_screens_render_backend_errors_instead_of_success_fallbacks(self):
        sales = SALES_SCREEN.read_text(encoding="utf-8")
        debt = DEBT_SCREEN.read_text(encoding="utf-8")
        self.assertIn("تعذر تأكيد التحصيل؛ لم يتم عرضه كتحصيل ناجح", sales)
        self.assertIn("onFailure { error = it.message ?: \"تعذر تسجيل التحصيل.\"" , debt)
        self.assertIn("if (error.isNotBlank())", sales)
        self.assertIn("if (error.isNotBlank())", debt)


if __name__ == "__main__":
    unittest.main()
