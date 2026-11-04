from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
STATE = ANDROID / "ReportsCenterUiState.kt"


def test_reports_center_interaction_state_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    state = STATE.read_text(encoding="utf-8")

    assert "val uiState = remember { ReportsCenterUiState(today) }" in screen
    for name in (
        "selectedPeriod", "startText", "endText", "search", "rowFilter",
        "currencyFilter", "statusFilter", "accountFilter", "customerFilter",
        "selectedCategory", "selectedReport", "exportRows", "exportTitle",
        "error", "supplierFilter", "cloudReload",
    ):
        assert f"val {name}:" in state
        assert f"by uiState.{name}" in screen
