from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
ERROR_MAPPER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CloudErrorMapper.kt"
SUPABASE_CLIENT = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupabaseClient.kt"
SALES_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt"
DEBT_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt"


class CommercialCloudActionableErrorTests(unittest.TestCase):
    def test_missing_rpc_schema_cache_error_is_actionable_and_warns_against_new_request(self):
        error_mapper = ERROR_MAPPER.read_text(encoding="utf-8")
        self.assertIn('code == "PGRST202"', error_mapper)
        self.assertIn("دالة قاعدة البيانات المطلوبة غير منشورة", error_mapper)
        self.assertIn("لا تنشئ عملية بديلة بمفتاح جديد", error_mapper)

    def test_ambiguous_network_failure_keeps_retry_safe(self):
        client = SUPABASE_CLIENT.read_text(encoding="utf-8")
        self.assertIn("انتهت مهلة الاتصال ولم يتمكن التطبيق من تأكيد النتيجة", client)
        self.assertIn("لا تبدأ عملية جديدة بمفتاح مختلف", client)
        self.assertIn("انقطع الاتصال قبل تأكيد النتيجة من الخادم", client)

    def test_expired_refresh_session_prompts_reauthentication(self):
        error_mapper = ERROR_MAPPER.read_text(encoding="utf-8")
        self.assertIn('message.contains("invalid refresh token"', error_mapper)
        self.assertIn('message.contains("refresh token not found"', error_mapper)
        self.assertIn('message.contains("invalid_grant"', error_mapper)
        self.assertIn('code == "refresh_token_not_found"', error_mapper)
        self.assertIn("انتهت جلسة الحساب أو لم تعد صالحة", error_mapper)

    def test_overpayment_and_stock_race_have_specific_user_messages(self):
        error_mapper = ERROR_MAPPER.read_text(encoding="utf-8")
        self.assertIn("collection_exceeds_outstanding_balance", error_mapper)
        self.assertIn("رُفض التحصيل لأن المبلغ يتجاوز الرصيد المستحق", error_mapper)
        self.assertIn("insufficient_inventory", error_mapper)
        self.assertIn("حدّث المخزون قبل المحاولة مجددًا", error_mapper)

    def test_sales_and_debt_screens_render_backend_errors_instead_of_success_fallbacks(self):
        sales = SALES_SCREEN.read_text(encoding="utf-8")
        debt = DEBT_SCREEN.read_text(encoding="utf-8")
        self.assertIn("تعذر تأكيد التحصيل؛ لم يتم عرضه كتحصيل ناجح", sales)
        self.assertIn("onFailure { error = it.message ?: \"تعذر تسجيل التحصيل.\"" , debt)
        self.assertIn("if (error.isNotBlank())", sales)
        self.assertIn("if (error.isNotBlank())", debt)


if __name__ == "__main__":
    unittest.main()
