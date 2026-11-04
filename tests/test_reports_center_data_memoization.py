from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterData.kt"


def test_report_input_projections_are_memoized_by_their_complete_inputs():
    data = DATA_STATE.read_text(encoding="utf-8")

    assert "remember(cloudPurchases) {\n        cloudPurchases.activeSupplierInvoiceCandidates()" in data
    assert "remember(activeSupplierInvoiceCandidates) {\n        activeSupplierInvoiceCandidates.activeSupplierObligationCandidates()" in data
    assert "remember(supplierObligationCandidates, endExclusive)" in data
    assert "remember(cloudPurchases, cloudSupplierPayments, supplierFilter)" in data
    assert "remember(sales, dateError, startMillis, endExclusive)" in data
    assert "remember(cloudSalesDocuments, dateError, startMillis, endExclusive)" in data
    assert "remember(cloudMode, settlements, startMillis, endExclusive)" in data
    assert "remember(cloudMode, expenses, startMillis, endExclusive)" in data
    assert "remember(products)" in data


def test_selected_sales_and_ledger_rows_reuse_results_until_inputs_change():
    data = DATA_STATE.read_text(encoding="utf-8")

    sales_block = data.split("val (salesRowsCalculation, salesRowsCalculationError) = remember(", 1)[1].split(
        "val (ledgerRowsCalculation, ledgerRowsCalculationError)", 1
    )[0]
    ledger_block = data.split("val (ledgerRowsCalculation, ledgerRowsCalculationError) = remember(", 1)[1].split(
        "salesRowsArithmeticError = salesRowsCalculationError", 1
    )[0]

    for dependency in (
        "selectedReport", "cloudMode", "inPeriodCloudSalesDocuments", "inPeriodSales",
        "cloudSalesDocuments", "sales", "startMillis", "endExclusive", "startText",
        "endText", "startDate", "endDate", "zone"
    ):
        assert dependency in sales_block

    for dependency in (
        "selectedReport", "ledgerStatementError", "cloudLedgerLines", "cloudJournals",
        "startDate", "endDate", "startMillis", "endExclusive", "startText", "endText",
        "accountFilter"
    ):
        assert dependency in ledger_block

    assert "salesRowsArithmeticError = salesRowsCalculationError" in data
    assert "ledgerRowsArithmeticError = ledgerRowsCalculationError" in data


def test_currency_summary_is_memoized_without_changing_its_fail_closed_guard():
    data = DATA_STATE.read_text(encoding="utf-8")
    block = data.split("val currencyTotalCalculation = remember(", 1)[1].split(
        "val currencyTotal =", 1
    )[0]

    for dependency in (
        "dateError", "cloudMode", "cloudLoading", "salesDocumentsLoadError",
        "inPeriodCloudSalesDocuments", "inPeriodSales"
    ):
        assert dependency in block

    assert "calculateReportCurrencyTotals(amounts)" in block
    assert "currencyTotalCalculation.isFailure" in data
