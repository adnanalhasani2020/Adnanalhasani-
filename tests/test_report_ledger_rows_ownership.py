from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportLedgerRows.kt"


def test_posted_ledger_report_rows_have_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildLedgerReportRows(" in data
    assert "buildLedgerReportRows(" not in screen
    assert "ledgerStatementError = ledgerStatementError" in data
    for marker in (
        '"قائمة الدخل والميزانية العمومية" ->',
        '"ميزان المراجعة حسب الحساب" ->',
        '"حركة دفتر الأستاذ العام", "كشف حساب محاسبي" ->',
        '"تفاصيل القيود المرحلة" ->',
        '"سجل القيود المحاسبية" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "reportStatementClass" in rows
    assert "reportAccountNet" in rows
    assert "accountQuery.isNotBlank()" in rows
