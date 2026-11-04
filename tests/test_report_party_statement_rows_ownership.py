from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportPartyStatementRows.kt"


def test_customer_and_supplier_statement_row_assembly_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildPartyStatementReportRows(" in data
    assert "buildPartyStatementReportRows(" not in screen
    assert "customerStatementError = customerStatementError" in data
    assert "supplierStatementError = supplierStatementError" in data
    for marker in (
        '"الرصيد المحاسبي الكامل للعملاء" ->',
        '"أرصدة الموردين والمستحقات" ->',
        '"أرصدة العملاء وكشف الحساب" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "cloudCustomerPartyAccounts" in rows
    assert "cloudSupplierPartyAccounts" in rows
    assert "cloudLedgerLines.filter" in rows
    assert "buildCustomerOperationalStatementMovements" in rows
    assert "sumReportAmountsExactly" in rows
