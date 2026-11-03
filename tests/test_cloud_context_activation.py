from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"


def test_activity_profile_context_is_reloaded_when_returning_to_store():
    source = MAIN.read_text(encoding="utf-8")
    assert "var cloudContextRevision by remember { mutableIntStateOf(0) }" in source
    assert "remember(cloudContextRevision)" in source
    assert "onBack = { showActivityProfile = false; cloudContextRevision++ }" in source
    assert "onCatalog = { showActivityProfile = false; cloudContextRevision++; showCatalog = true }" in source


def test_valid_cloud_context_fails_closed_before_cloud_reads():
    source = MAIN.read_text(encoding="utf-8")
    effect = source.split("LaunchedEffect(cloudContextSnapshot, cloudReloadToken)", 1)[1].split(
        "Scaffold(", 1
    )[0]
    assert effect.index("cloudMode = cloudContextHasAny") < effect.index("storeCloudIo.execute")
    assert "inventory.clear()" in effect
    assert "sales.clear()" in effect
    assert "settlements.clear()" in effect
    assert "expenses.clear()" in effect
    assert "cloudInventoryError = it.message" in effect


def test_cloud_inventory_error_has_retry_without_falling_back_to_local_mode():
    source = MAIN.read_text(encoding="utf-8")
    assert 'Text("إعادة المحاولة")' in source
    assert "cloudReloadToken++" in source
    assert "cloudMode = cloudContextHasAny" in source

def test_partial_cloud_context_never_loads_or_displays_local_data():
    source = MAIN.read_text(encoding="utf-8")
    assert "val cloudContextHasAny = cloudContextSnapshot.first.isNotBlank()" in source
    assert "cloudContextSnapshot.second.isNotBlank() || cloudContextSnapshot.third.isNotBlank()" in source
    assert "mutableStateOf(cloudContextHasAny)" in source
    assert "if (!cloudContextHasAny) addAll(loadSales(context))" in source
    assert "if (!cloudContextExists) {" in source
    assert "بيانات المؤسسة السحابية غير مكتملة" in source
    assert "cloudContextHasAny && cloudInventoryError.isNotBlank()" in source
