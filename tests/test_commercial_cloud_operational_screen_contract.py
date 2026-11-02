from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudOperationalScreen.kt"
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"


class CommercialCloudOperationalScreenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")

    def test_operational_queries_are_explicitly_allowlisted(self):
        function = self.repository.split(
            "internal fun loadCommercialCloudRecords", 1
        )[1]
        expected = {
            '"customers" -> Triple("commercial_customers"',
            '"suppliers" -> Triple("commercial_suppliers"',
            '"purchases" -> Triple("commercial_purchase_orders"',
            '"payments" -> Triple("commercial_payments"',
            '"returns" -> Triple("commercial_sales_returns"',
            '"journals" -> Triple("commercial_journal_entries"',
            '"accounts" -> Triple("commercial_ledger_accounts"',
        }
        for projection in expected:
            with self.subTest(projection=projection):
                self.assertIn(projection, function)
        self.assertIn('else -> throw IllegalArgumentException("هذه الشاشة غير مدعومة.")', function)

    def test_queries_are_organization_scoped_and_read_only(self):
        function = self.repository.split(
            "internal fun loadCommercialCloudRecords", 1
        )[1].split("\ninternal fun ", 1)[0]
        self.assertIn('organization_id=eq.$orgId', function)
        self.assertIn('commercialCloudPagedGet(', function)
        self.assertIn('organization_id=eq.$orgId', function)
        self.assertNotIn('"POST"', function)
        self.assertNotIn('"PATCH"', function)
        self.assertNotIn('"DELETE"', function)

    def test_every_cloud_screen_is_reachable_from_more_menu(self):
        expected_routes = {
            '"دليل العملاء السحابي" -> onCloudOperational("customers")',
            '"دليل الموردين" -> onCloudOperational("suppliers")',
            '"أوامر الشراء" -> onCloudPurchases()',
            '"السندات والمدفوعات" -> onCloudOperational("payments")',
            '"مرتجعات المبيعات" -> onCloudOperational("returns")',
            '"القيود المحاسبية" -> onCloudOperational("journals")',
            '"دليل الحسابات" -> onCloudOperational("accounts")',
        }
        for route in expected_routes:
            with self.subTest(route=route):
                self.assertIn(route, self.main)

    def test_screen_discloses_read_only_boundary_and_error_states(self):
        for text in ("قراءة فقط", "تعذر تحميل بيانات السحابة", "إعادة المحاولة"):
            with self.subTest(text=text):
                self.assertIn(text, self.screen)
        self.assertIn("لم يتم إنشاء بيانات تجريبية تلقائيًا", self.screen)

    def test_sales_returns_use_dedicated_schema_backed_workflow(self):
        returns_screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt").read_text(encoding="utf-8")
        for token in (
            "loadCommercialCloudSaleReturns(context)",
            "loadCommercialCloudReturnableSales(context)",
            "requestCommercialCloudSaleReturn(context",
            "commercial_request_sale_return",
            "remainingQuantity",
            "alreadyRequested",
            "priorReturns",
            "المتبقي للإرجاع",
            "إنشاء طلب مرتجع من فاتورة مكتملة",
            "التحقق النهائي من الكميات المرتجعة يتم في قاعدة البيانات",
            "إعادة المخزون للمؤسسات متوقفة آليًا",
            "decideCommercialCloudSaleReturn",
            "inspectCommercialCloudSaleReturnItem",
            "restockCommercialCloudSaleReturn",
            "رد المبلغ ما زال معلقًا",
            "commercial_sales_returns",
        ):
            with self.subTest(token=token):
                self.assertIn(token, returns_screen + self.repository)
        self.assertIn('cloudOperationalPage == "returns" -> CommercialCloudReturnsScreen', self.main)
        self.assertIn("commercial_sale_return_items", self.repository)
        self.assertIn("commercial_sales!inner(receipt_number,customer:commercial_customers(display_name))", self.repository)

    def test_projection_columns_are_not_built_from_user_input(self):
        function = self.repository.split(
            "internal fun loadCommercialCloudRecords", 1
        )[1]
        self.assertRegex(function, r'val config = when \(screenKey\)')
        self.assertNotRegex(function, r'\$\{screenKey\}.*select=')

    def test_cloud_debt_collection_freezes_request_inputs_while_submitting(self):
        debt = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt").read_text(encoding="utf-8")
        self.assertIn("enabled = !busy", debt)
        self.assertIn("val selectedMethod = method", debt)
        self.assertIn("recordCommercialCloudCustomerPayment(context, target.saleId, value, selectedMethod)", debt)
        self.assertNotIn("recordCommercialCloudCustomerPayment(context, target.saleId, value, method)", debt)


if __name__ == "__main__":
    unittest.main()
