from pathlib import Path

from catalog_source_helpers import catalog_android_source


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def read(name):
    return (ANDROID / name).read_text(encoding="utf-8")


def test_catalog_models_have_one_shared_owner():
    models = read("CatalogModels.kt")
    screen = read("CatalogScreen.kt")

    for name in (
        "CatalogUnit", "CatalogRuling", "CatalogProduct", "CatalogCategory",
        "CatalogBranchOption", "CatalogWarehouseOption", "NearbyCatalogItem",
        "InvoicePurchaseRequestRow", "InvoicePurchaseUnmappedLine",
        "InvoicePurchaseOffering", "DirectoryUnit", "PendingTransfer",
    ):
        assert f"data class {name}" in models
        assert f"data class {name}" not in screen


def test_catalog_screen_keeps_existing_model_contracts():
    screen = read("CatalogScreen.kt")
    assert "internal fun CatalogScreen(" in screen
    assert "CatalogProduct" in screen
    assert "availableUnits: List<CatalogUnit>" in read("CatalogModels.kt")


def test_catalog_uses_shared_transport_and_session_store():
    transport = read("CatalogCloudTransport.kt")
    auth = read("CatalogAuthWorkflow.kt")
    screen = read("CatalogScreen.kt")
    session = read("CommercialCloudSession.kt")
    assert "SupabaseClient.request(" in transport
    assert 'method = "POST"' in transport
    assert 'method = "GET"' in transport
    assert "commercialCloudSessionPreferences(context)" in auth
    assert "EncryptedSharedPreferences" not in screen
    assert "HttpURLConnection" not in screen
    assert "fun commercialCloudSessionPreferences" in session


def test_catalog_login_ui_has_a_dedicated_composable_owner():
    screen = read("CatalogScreen.kt")
    login = read("CatalogLoginPanel.kt")
    assert "CatalogLoginPanel(" in screen
    assert "internal fun CatalogLoginPanel(" in login
    assert "تسجيل الدخول إلى حساب Supabase" in login
    assert "لا تستخدم مفتاح service_role في التطبيق" in login
    assert "OutlinedTextField(email," not in screen


def test_catalog_market_response_parsing_has_a_dedicated_owner():
    workflow = read("CatalogMarketWorkflow.kt")
    mapper = read("CatalogMarketData.kt")
    for name in ("parseCatalogMarketContext", "parseNearbyCatalogItems", "parseCatalogMarketPriceHints"):
        assert f"{name}(" in mapper
        assert f"{name}(" in workflow
    assert "val page = parseNearbyCatalogItems(raw)" in workflow
    assert "val hints = parseCatalogMarketPriceHints(raw)" in workflow


def test_catalog_discovery_product_mapping_has_a_dedicated_owner():
    workflow = read("CatalogBrowseWorkflow.kt")
    mapper = read("CatalogProductMapper.kt")
    assert "parseCatalogProducts(raw, signedInUserId())" in workflow
    assert "internal fun parseCatalogProducts(" in mapper
    assert "val pageItems=List(json.length())" not in workflow
    assert "setOffset(requestedOffset + pageItems.size)" in workflow
    assert "val more = pageItems.size == 100" in workflow


def test_catalog_directory_response_mapping_has_a_dedicated_owner():
    workflow = read("CatalogDirectoryWorkflow.kt")
    mapper = read("CatalogDirectoryMapper.kt")
    assert "parseCatalogDirectory(raw)" in workflow
    assert "internal fun parseCatalogDirectory(raw: String)" in mapper
    assert "CatalogDirectoryData" in mapper
    assert "val directory = parseCatalogDirectory(raw)" in workflow
    assert "val categoryNames=buildMap" not in workflow
    assert "CatalogActivityNode(node.optString" not in workflow


def test_catalog_invoice_and_transfer_response_mappers_have_dedicated_owner():
    invoice = read("CatalogInvoiceWorkflow.kt")
    transfer = read("CatalogTransferWorkflow.kt")
    mapper = read("CatalogWorkflowMappers.kt")
    assert "parseInvoicePurchaseRequests(raw)" in invoice
    assert "parsePendingCatalogTransfers(raw)" in transfer
    assert "internal fun parseInvoicePurchaseRequests(raw: String)" in mapper
    assert "internal fun parsePendingCatalogTransfers(raw: String)" in mapper
    assert "InvoicePurchaseRequestRow(row.optString" not in invoice
    assert "PendingTransfer(v.optString" not in transfer


def test_catalog_invoice_mapping_response_has_a_dedicated_mapper():
    workflow = read("CatalogInvoiceWorkflow.kt")
    mapper = read("CatalogInvoiceMappingMapper.kt")
    assert "parseCatalogInvoiceMapping(raw)" in workflow
    assert "internal fun parseCatalogInvoiceMapping(raw: String)" in mapper
    assert "state.mappingLines = mapping.lines" in workflow
    assert "InvoicePurchaseUnmappedLine(row.optString" not in workflow
    assert "CatalogWarehouseOption(row.optString" not in workflow


def test_catalog_market_unit_validation_has_a_dedicated_owner():
    workflow = read("CatalogMarketWorkflow.kt")
    configuration = read("CatalogMarketConfiguration.kt")
    assert "buildCatalogConfigurationItems(selectedIds, JSONArray(unitRaw))" in workflow
    assert "internal fun buildCatalogConfigurationItems(" in configuration
    assert "val baseCandidates" not in workflow
    assert "commercial_catalog_product_units" in workflow
