from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
MAPPERS = ANDROID / "ReportDomainMappers.kt"


def test_pure_report_mappers_have_a_single_non_ui_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    mappers = MAPPERS.read_text(encoding="utf-8")

    for marker in (
        "toPriceVarianceInput",
        "toCostProfitInput",
        "toCustomerStatementInputs",
        "reportLineAmount",
        "toReportLines",
        "ReportSupplierMovement",
    ):
        assert marker in mappers
        assert marker not in screen


def test_report_screen_consumes_the_extracted_mapper_contracts():
    screen = SCREEN.read_text(encoding="utf-8")
    assert "toPriceVarianceInput()" in screen
    assert "toCostProfitInput()" in screen
    assert "toCustomerStatementInputs()" in screen
    assert "reportLineAmount(row, cloudMode)" in screen
    assert "toReportLines(today.toString())" in screen
