from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
CONTRACT = ROOT / "android-commercial/docs/reports-data-contract.md"


class ReportsCenterSourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.contract = CONTRACT.read_text(encoding="utf-8")

    def test_cloud_operational_reports_use_authoritative_sources(self):
        for source in (
            "loadCommercialCloudSaleDocuments(context)",
            "loadCommercialCloudPurchaseDocuments(context)",
            "loadCommercialCloudSaleReturns(context)",
            'loadCommercialCloudRecords(context, "payments")',
            "loadCommercialCloudExpenses(context)",
        ):
            with self.subTest(source=source):
                self.assertIn(source, self.screen)

    def test_ledger_projection_is_organization_scoped_and_posted_only(self):
        loader = self.repository.split(
            "internal fun loadCommercialCloudPostedJournalLines", 1
        )[1]
        self.assertIn("status=eq.posted", loader)
        self.assertIn("organization_id=eq.$orgId", loader)
        self.assertIn("commercial_journal_lines", loader)
        self.assertIn("commercial_ledger_accounts", loader)
        self.assertIn("debit_minor", loader)
        self.assertIn("credit_minor", loader)

    def test_financial_balances_not_supported_by_complete_ledger_remain_gated(self):
        self.assertIn('ReportCatalogItem("أرصدة العملاء وكشف الحساب"', self.screen)
        self.assertIn('"حركات الفواتير والتحصيل والمرتجعات والتسويات", false', self.screen)
        self.assertIn('ReportCatalogItem("أرصدة الموردين والمستحقات"', self.screen)
        self.assertIn('"فواتير المورد والمدفوعات والمرتجعات", false', self.screen)
        self.assertIn("Deliberately unavailable as formal statements", self.contract)

    def test_xlsx_export_is_real_ooxml_and_right_to_left(self):
        self.assertIn("ZipOutputStream(output)", self.screen)
        self.assertIn("[Content_Types].xml", self.screen)
        self.assertIn("xl/workbook.xml", self.screen)
        self.assertIn("xl/worksheets/sheet1.xml", self.screen)
        self.assertIn('rightToLeft=\\"1\\"', self.screen)
        self.assertIn("inlineStr", self.screen)
        self.assertIn('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', self.screen)
        self.assertIn("Excel XLSX", self.screen)

    def test_csv_contains_period_source_timestamp_and_arabic_bom(self):
        self.assertIn('writer.write("\\uFEFF")', self.screen)
        self.assertIn('add(listOf("الفترة", startText + " — " + endText))', self.screen)
        self.assertIn('add(listOf("مصدر البيانات"', self.screen)
        self.assertIn('add(listOf("تاريخ التصدير"', self.screen)


if __name__ == "__main__":
    unittest.main()
