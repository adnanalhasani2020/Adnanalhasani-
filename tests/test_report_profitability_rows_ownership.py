from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportProfitabilityRows.kt"
VALIDATION = ANDROID / "ReportProfitabilityValidation.kt"


def test_profitability_report_rows_have_a_dedicated_mapper():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildItemProfitabilityRows(" in data
    assert "buildItemProfitabilityRows(" not in screen
    assert "buildCostProfitRows(" in data
    assert '"ربحية الأصناف بعد المرتجعات" -> buildItemProfitabilityRows' in data
    assert '"تكلفة البضاعة والربح" -> buildCostProfitRows' in data
    assert "calculateCostProfitReportTotals" in rows
    assert "toCostProfitInput()" in rows
    assert "cost_total_minor" in rows
    assert "calculateItemProfitability" not in rows


def test_historical_cost_profit_validation_has_a_dedicated_owner():
    data = DATA_STATE.read_text(encoding="utf-8")
    validation = VALIDATION.read_text(encoding="utf-8")

    assert "reportCostProfitValidationError(" in data
    assert "reportCostProfitValidationError(" in validation
    assert "costProfitError = when" not in data
    for marker in (
        "توجد بنود مبيعات في الفترة بلا تكلفة تاريخية مسجلة؛ لا يمكن اعتماد الربح.",
        "توجد مرتجعات مكتملة تفتقد مبلغ اعتماد أو تكلفة البند التاريخية اللازمة لتسوية الربح.",
        "تعذر مطابقة عملة أو فاتورة أحد المرتجعات مع سجل البيع الأصلي؛ تم حجب الربح.",
        "توجد مرتجعات لفواتير ضريبية؛ لم تُفصل حصة الضريبة في مبلغ الاسترداد، لذلك حُجب صافي الربح.",
        "تجاوزت مجاميع المبيعات أو التكلفة أو المرتجعات نطاق الأرقام الدقيق، أو تعذر تسويتها؛ تم حجب الربح بدل إظهار إجمالي ملتف أو ناقص.",
    ):
        assert marker in validation
    assert "calculateCostProfitReportTotals(" in validation
