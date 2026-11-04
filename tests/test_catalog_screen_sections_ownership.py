from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
SECTIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreenSections.kt"
PRODUCT_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogProductFormState.kt"
INVOICE_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogInvoiceTransferState.kt"


class CatalogScreenSectionsOwnershipTests(unittest.TestCase):
    def test_category_and_unit_editors_have_separate_presentation_owners(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        self.assertIn('"category" -> CatalogCategorySection(', screen)
        self.assertIn('"unit" -> CatalogUnitSection(', screen)
        self.assertIn("internal fun CatalogCategorySection(", sections)
        self.assertIn("internal fun CatalogUnitSection(", sections)
        self.assertIn("onCreateCategory: () -> Unit", sections)
        self.assertIn("onCreateUnit: () -> Unit", sections)

    def test_product_authoring_form_has_dedicated_state_and_presentation_owners(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        state = PRODUCT_STATE.read_text(encoding="utf-8")
        self.assertIn('"product" -> CatalogProductFormSection(', screen)
        self.assertIn("internal class CatalogProductFormState", state)
        self.assertIn("internal fun CatalogProductFormSection(", sections)
        self.assertIn("onCreateProduct: () -> Unit", sections)

    def test_invoice_purchase_review_has_separate_presentation_and_state(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        state = INVOICE_STATE.read_text(encoding="utf-8")
        self.assertIn('"invoice_transfer" -> CatalogInvoiceTransferSection(', screen)
        self.assertIn("internal fun CatalogInvoiceTransferSection(", sections)
        self.assertIn("internal class CatalogInvoiceTransferState", state)
        self.assertIn("onReviewInvoicePurchaseRequest: (String, Boolean) -> Unit", sections)

    def test_market_discovery_state_has_a_dedicated_owner(self):
        screen = SCREEN.read_text(encoding="utf-8")
        state_file = SCREEN.with_name("CatalogMarketUiState.kt").read_text(encoding="utf-8")
        self.assertIn("val marketUiState = remember { CatalogMarketUiState() }", screen)
        self.assertIn("internal class CatalogMarketUiState", state_file)
        self.assertIn("var nearbyMarketItems", state_file)
        self.assertIn("var selectedBranchId", state_file)
        self.assertNotIn("var nearbyMarketItems by remember", screen)

    def test_market_discovery_presentation_is_callback_driven(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        market_section = SCREEN.with_name("CatalogMarketDiscoverySection.kt").read_text(encoding="utf-8")
        self.assertIn("CatalogMarketDiscoverySection(", screen)
        self.assertIn("internal fun CatalogMarketDiscoverySection(", market_section)
        self.assertIn("onSaveCoordinates: (String?, String?) -> Unit", market_section)
        self.assertIn("onLoadNearbyCatalog: (Boolean) -> Unit", market_section)
        self.assertNotIn("commercial_update_location_coordinates", market_section)
        self.assertNotIn("catalogHttp(", market_section)

    def test_product_result_card_has_a_dedicated_presentation_owner(self):
        screen = SCREEN.read_text(encoding="utf-8")
        card = SCREEN.with_name("CatalogProductResultCard.kt").read_text(encoding="utf-8")
        self.assertIn("CatalogProductResultCard(", screen)
        self.assertIn("internal fun CatalogProductResultCard(", card)
        self.assertIn("onSelectionChange: (Boolean) -> Unit", card)
        self.assertIn("onAddToInventory: (CatalogProduct, String, String, String, String, String) -> Unit", card)
        self.assertNotIn("catalogHttp(", card)

    def test_transfer_presentation_is_separate_and_keeps_actions_callback_driven(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        self.assertIn('"transfer" -> CatalogTransferSection(', screen)
        self.assertIn("internal fun CatalogTransferSection(", sections)
        self.assertIn("onRespondToTransfer: (String, Boolean) -> Unit", sections)
        self.assertIn("onLoadPendingTransfers: () -> Unit", sections)


if __name__ == "__main__":
    unittest.main()
