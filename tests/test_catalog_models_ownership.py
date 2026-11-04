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
    assert "CatalogUnit" in screen

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

    assert "JSONArray(raw)" not in screen
