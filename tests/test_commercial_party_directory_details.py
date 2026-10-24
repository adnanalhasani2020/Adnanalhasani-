from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudOperationalScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
DOCS = ROOT / "android-commercial/docs/cloud-backed-screen-delivery.md"


class CommercialPartyDirectoryDetailsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.docs = DOCS.read_text(encoding="utf-8")

    def test_customer_and_supplier_queries_use_existing_fields(self):
        self.assertIn('"customers" -> Triple("commercial_customers", "id,display_name,phone,email,state,created_at"', self.repository)
        self.assertIn('"suppliers" -> Triple("commercial_suppliers", "id,display_name,supplier_code,phone,email,state,payment_terms_days,created_at"', self.repository)

    def test_directory_cards_render_optional_details(self):
        self.assertIn('if (row.details.isNotEmpty())', self.screen)
        self.assertIn('row.details.forEach { detail ->', self.screen)
        self.assertIn('"البريد الإلكتروني: $it"', self.repository)
        self.assertIn('"شروط السداد: $it يومًا"', self.repository)

    def test_missing_values_are_omitted_and_no_write_path_added(self):
        self.assertIn('it.isNotBlank() && it != "null"', self.repository)
        self.assertIn('val details: List<String> = emptyList()', self.repository)

    def test_change_is_documented_as_read_only(self):
        self.assertIn("read-only presentation change", self.docs)
        self.assertIn("does not enable unverified customer/supplier writes", self.docs)


if __name__ == "__main__":
    unittest.main()
