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
