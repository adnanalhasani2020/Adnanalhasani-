from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
RETURNS_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReturnsRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudOperationalScreen.kt"
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MAIN_ACTIVITY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
MAIN_ACTIVITY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"


class CommercialCloudOperationalScreenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.returns_repository = RETURNS_REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8") + MAIN_ACTIVITY.read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt").read_text(encoding="utf-8")

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
                self.assertIn(token, returns_screen + self.returns_repository)
        self.assertIn('cloudOperationalPage == "returns" -> CommercialCloudReturnsScreen', self.main)
        self.assertIn("commercial_sale_return_items", self.returns_repository)
        self.assertIn("commercial_sales!inner(receipt_number,activity_id,customer:commercial_customers(display_name))", self.returns_repository)

    def test_integer_sale_returns_preserve_invoice_unit_snapshots_and_retry_safely(self):
        returns_screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt").read_text(encoding="utf-8")
        migration = (ROOT / "supabase/migrations/20261105123000_commercial_integer_quantity_enforcement.sql").read_text(encoding="utf-8")
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn("maxCommercialReturnQuantity", returns_screen + quantity_math)
        self.assertIn("parseCommercialCheckoutQuantity", returns_screen)
        self.assertIn("quantities: Map<String, BigDecimal>", self.returns_repository)
        self.assertIn("commercial_cloud_pending_return_v1", self.returns_repository)
        self.assertIn("quantity.toPlainString()", self.returns_repository)
        self.assertIn("v_qty <> trunc(v_qty)", migration)
        self.assertIn("quantity.stripTrailingZeros().scale() <= 0", (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReturnsRepository.kt").read_text(encoding="utf-8"))
        self.assertIn("Regex(\"^[0-9]{1,6}$\")", quantity_math)
        self.assertIn("quantity_in_stock_units", self.returns_repository)
        self.assertIn("sellingUnitFactor = sellingUnitFactor", self.returns_repository)
        self.assertIn("item.sellingUnitFactor", returns_screen)
        self.assertIn("v_sale_item.selling_unit_code IS DISTINCT FROM v_unit_code", migration)
        self.assertIn("v_sale_item.quantity_in_stock_units::numeric / v_sale_item.selling_quantity", migration)
        self.assertNotIn("FROM public.commercial_offering_sell_units", migration)

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
