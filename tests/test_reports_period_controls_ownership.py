from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
CONTROLS = ANDROID / "ReportsPeriodControls.kt"


def test_period_controls_are_presentation_only_and_preserve_date_presets():
    screen = SCREEN.read_text(encoding="utf-8")
    controls = CONTROLS.read_text(encoding="utf-8")

    assert "ReportsPeriodControls(" in screen
    assert "dateError = dateError" in screen
    assert "onRangeChanged = { start, end -> startText = start; endText = end }" in screen
    assert "LocalDate.now()" in controls
    for period in ("اليوم", "الأسبوع الحالي", "الشهر الحالي", "الربع السابق", "السنة السابقة"):
        assert period in controls
    assert "dateError" in controls
    assert "reportDateMillis" not in controls
    assert "loadCommercialCloud" not in controls
