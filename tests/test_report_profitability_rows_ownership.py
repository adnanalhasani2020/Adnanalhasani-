from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
ROWS = ANDROID / "ReportProfitabilityRows.kt"


def test_profitability_report_rows_have_a_dedicated_mapper():
    screen = SCREEN.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildItemProfitabilityRows(" in screen
    assert "buildCostProfitRows(" in screen
    assert '"ربحية الأصناف بعد المرتجعات" -> buildItemProfitabilityRows' in screen
    assert '"تكلفة البضاعة والربح" -> buildCostProfitRows' in screen
    assert "calculateCostProfitReportTotals" in rows
    assert "toCostProfitInput()" in rows
    assert "cost_total_minor" in rows
    assert "calculateItemProfitability" not in rows
