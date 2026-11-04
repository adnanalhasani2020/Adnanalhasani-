from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "CatalogScreen.kt"
MODELS = ANDROID / "CatalogModels.kt"


def test_catalog_models_have_one_shared_owner():
    models = MODELS.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")

    for name in (
        "CatalogUnit",
        "CatalogRuling",
        "CatalogProduct",
        "CatalogCategory",
        "CatalogBranchOption",
        "CatalogWarehouseOption",
        "NearbyCatalogItem",
        "InvoicePurchaseRequestRow",
        "InvoicePurchaseUnmappedLine",
        "InvoicePurchaseOffering",
        "DirectoryUnit",
        "PendingTransfer",
    ):
        assert f"data class {name}" in models
        assert f"data class {name}" not in screen


def test_catalog_screen_keeps_existing_model_contracts():
    screen = SCREEN.read_text(encoding="utf-8")
    assert "internal fun CatalogScreen(" in screen
    assert "CatalogProduct" in screen
    assert "availableUnits: List<CatalogUnit>" in MODELS.read_text(encoding="utf-8")

def test_catalog_uses_the_shared_supabase_transport_and_session_store():
    screen = SCREEN.read_text(encoding="utf-8")
    transport = (ANDROID / "CatalogCloudTransport.kt").read_text(encoding="utf-8")
    session = (ANDROID / "CommercialCloudSession.kt").read_text(encoding="utf-8")

    assert "SupabaseClient.request(" in transport
    assert 'method = "POST"' in transport
    assert 'method = "GET"' in transport
    assert "commercialCloudSessionPreferences(context)" in screen
    assert "EncryptedSharedPreferences" not in screen
    assert "HttpURLConnection" not in screen
    assert "fun commercialCloudSessionPreferences" in session


if __name__ == "__main__":
    unittest.main()


def test_catalog_login_ui_has_a_dedicated_composable_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    login = (ANDROID / "CatalogLoginPanel.kt").read_text(encoding="utf-8")

    assert "CatalogLoginPanel(" in screen
    assert "internal fun CatalogLoginPanel(" in login
    assert "تسجيل الدخول إلى حساب Supabase" in login
    assert "لا تستخدم مفتاح service_role في التطبيق" in login
    assert "OutlinedTextField(email," not in screen



def test_catalog_market_response_parsing_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    market_data = (ANDROID / "CatalogMarketData.kt").read_text(encoding="utf-8")

    for name in (
        "parseCatalogMarketContext",
        "parseNearbyCatalogItems",
        "parseCatalogMarketPriceHints",
    ):
        assert f"{name}(" in market_data
        assert f"{name}(" in screen

    assert "val page = parseNearbyCatalogItems(raw)" in screen
    assert "val hints = parseCatalogMarketPriceHints(raw)" in screen



def test_catalog_discovery_product_mapping_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    mapper = (ANDROID / "CatalogProductMapper.kt").read_text(encoding="utf-8")

    assert "parseCatalogProducts(raw, signedInUserId)" in screen
    assert "internal fun parseCatalogProducts(" in mapper
    assert "val pageItems=List(json.length())" not in screen
    assert "catalogOffset = requestedOffset + pageItems.size" in screen
    assert "catalogHasMore = pageItems.size == 100" in screen


def test_catalog_directory_response_mapping_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    mapper = (ANDROID / "CatalogDirectoryMapper.kt").read_text(encoding="utf-8")

    assert "parseCatalogDirectory(raw)" in screen
    assert "internal fun parseCatalogDirectory(raw: String)" in mapper
    assert "CatalogDirectoryData" in mapper
    assert "val directory = parseCatalogDirectory(raw)" in screen
    assert "val categoryNames=buildMap" not in screen
    assert "CatalogActivityNode(node.optString" not in screen


def test_catalog_invoice_and_transfer_response_mappers_have_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    mapper = (ANDROID / "CatalogWorkflowMappers.kt").read_text(encoding="utf-8")

    assert "parseInvoicePurchaseRequests(raw)" in screen
    assert "parsePendingCatalogTransfers(raw)" in screen
    assert "internal fun parseInvoicePurchaseRequests(raw: String)" in mapper
    assert "internal fun parsePendingCatalogTransfers(raw: String)" in mapper
    assert "InvoicePurchaseRequestRow(row.optString" not in screen
    assert "PendingTransfer(v.optString" not in screen


def test_catalog_invoice_mapping_response_has_a_dedicated_mapper():
    screen = SCREEN.read_text(encoding="utf-8")
    mapper = (ANDROID / "CatalogInvoiceMappingMapper.kt").read_text(encoding="utf-8")

    assert "parseCatalogInvoiceMapping(raw)" in screen
    assert "internal fun parseCatalogInvoiceMapping(raw: String)" in mapper
    assert "invoiceMappingLines = mapping.lines" in screen
    assert "InvoicePurchaseUnmappedLine(row.optString" not in screen
    assert "CatalogWarehouseOption(row.optString" not in screen
