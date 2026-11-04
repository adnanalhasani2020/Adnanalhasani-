from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA = ANDROID / "ReportsCenterData.kt"
OPERATIONAL_VALIDATION = ANDROID / "ReportOperationalValidation.kt"
PROFITABILITY_VALIDATION = ANDROID / "ReportProfitabilityValidation.kt"
FINANCIAL_VALIDATION = ANDROID / "ReportFinancialValidation.kt"


def test_reports_center_screen_is_a_thin_coordinator_over_report_data_state():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA.read_text(encoding="utf-8")

    assert "rememberReportsCenterData(" in screen
    assert "val selectedRows = reportData.selectedRows" in screen
    assert "val sourceErrorForReport = reportData.sourceErrorForReport" in screen
    assert "val currencyTotal = reportData.currencyTotal" in screen
    assert "val currencyTotalError = reportData.currencyTotalError" in screen
    for marker in (
        "reportLedgerStatementValidationError(",
        "reportSupplierInvoiceVarianceError(",
        "reportSupplierInvoiceDetailValidationError(",
        "reportSupplierPurchaseSummaryValidationError(",
        "reportSupplierObligationsValidationError(",
        "reportCostProfitValidationError(",
        "reportItemProfitabilityValidationError(",
        "reportTaxDetailValidationError(",
        "reportCustomerStatementValidationError(",
        "reportSupplierStatementValidationError(",
        "reportCustomerOperationalStatementValidationError(",
        "reportCustomerCollectionsValidationError(",
        "reportUserSalesPerformanceValidationError(",
        "reportDailySalesTrendValidationError(",
        "reportExpenseValidationError(",
        "reportPaymentMethodAnalysisValidationError(",
        "calculateItemProfitability(",
        "calculateCustomerCollectionsByCustomer(",
        "calculateUserSalesPerformance(",
        "calculateDailySalesTrend(",
        "buildReportsCatalog(cloudMode)",
        "selectedRowsUnvalidated = buildInventoryReportRows(",
    ):
        assert marker in data
        assert marker not in screen
    assert "ReportsCenterData(" in data
    assert "sourceErrorForReport: (String) -> String" in data



def test_operational_and_item_profitability_validations_have_single_domain_owners():
    data = DATA.read_text(encoding="utf-8")
    operational = OPERATIONAL_VALIDATION.read_text(encoding="utf-8")
    profitability = PROFITABILITY_VALIDATION.read_text(encoding="utf-8")
    financial = FINANCIAL_VALIDATION.read_text(encoding="utf-8")

    for marker in (
        "reportCustomerOperationalStatementValidationError(",
        "reportCustomerCollectionsValidationError(",
        "reportUserSalesPerformanceValidationError(",
        "reportDailySalesTrendValidationError(",
        "reportExpenseValidationError(",
        "reportPaymentMethodAnalysisValidationError(",
    ):
        assert marker in operational
        assert marker in data
    assert "buildCustomerOperationalStatementMovements" in operational
    assert "reportItemProfitabilityValidationError(" in profitability
    assert "reportItemProfitabilityValidationError(" in data
    assert "val customerOperationalStatementError = when {" not in data
    assert "val customerCollectionsError = when {" not in data
    assert "val itemProfitabilityError = when {" not in data
    for marker in ("reportCurrentInventoryValuationValidationError(", "reportCashFlowValidationError("):
        assert marker in financial
        assert marker in data
    assert '"التقييم المالي الحالي للمخزون" -> when {' not in data
    assert '"التدفقات النقدية والصندوق" -> when {' not in data


def test_party_statement_validation_owns_exact_balance_preflight():
    data = DATA.read_text(encoding="utf-8")
    validation = (ANDROID / "ReportPartyStatementValidation.kt").read_text(encoding="utf-8")
    assert "reportPartyStatementArithmeticIsExact(" in validation
    assert "startDate = startDate" in data
    assert "endDate = endDate" in data
    assert "reportCustomerStatementValidationError(" in data
    assert "reportSupplierStatementValidationError(" in data
