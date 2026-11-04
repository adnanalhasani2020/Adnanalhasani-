from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
FILTERS = ANDROID / "ReportsFilterSection.kt"


def test_report_party_and_row_filters_have_a_dedicated_ui_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    filters = FILTERS.read_text(encoding="utf-8")

    assert "reportsFilterSection(" in screen
    assert "onAccountFilterChanged = { accountFilter = it }" in screen
    for marker in (
        "اسم المورد",
        "اسم العميل أو معرفه",
        "تصفية نتائج التقرير",
        "اسم أو مرجع المستند",
        "رمز الحساب أو اسمه",
    ):
        assert marker in filters
        assert marker not in screen
    assert "currencyFilter" in filters
    assert "statusFilter" in filters
