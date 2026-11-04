from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
DATA_STATE = ANDROID / "ReportsCenterData.kt"
ROWS = ANDROID / "ReportSupplierRows.kt"
VALIDATION = ANDROID / "ReportSupplierValidation.kt"


def test_supplier_report_row_assembly_has_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    data = DATA_STATE.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildSupplierReportRows(" in data
    assert "buildSupplierReportRows(" not in screen
    for marker in (
        '"فروقات أسعار فواتير الموردين" ->',
        '"فواتير الشراء والمستحقات المسجلة" ->',
        '"مستحقات فواتير الموردين" ->',
        '"كشف حركات الموردين" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "calculateSupplierInvoicePriceVariance" in rows
    assert "calculateSupplierObligationTotals" in rows
    assert "cloudSupplierPayments" in rows
    assert "supplierFilter" in rows
    assert "Math.addExact" in rows


def test_supplier_invoice_and_obligation_validation_has_a_dedicated_owner():
    data = DATA_STATE.read_text(encoding="utf-8")
    validation = VALIDATION.read_text(encoding="utf-8")

    for marker in (
        "reportSupplierInvoiceVarianceError(",
        "reportSupplierInvoiceDetailValidationError(",
        "reportSupplierPurchaseSummaryValidationError(",
        "reportSupplierObligationsValidationError(",
    ):
        assert marker in data
        assert marker in validation
        assert marker not in SCREEN.read_text(encoding="utf-8")
    for marker in (
        "توجد فاتورة مورد بتاريخ تسجيل غير صالح؛ تعذر تحديد الفترة بدقة.",
        "توجد فاتورة أو مستحق مورد بمبلغ أو عملة غير متطابقة؛ تم حجب التقرير.",
        "توجد فاتورة مورد لا تتطابق بنودها أو إجمالياتها مع الضريبة والشحن؛ تم حجب ملخص الموردين.",
        "تعذر مطابقة عملات الالتزامات أو تجاوزت مجاميعها نطاق الأرقام الدقيق؛ تم حجب المستحقات.",
    ):
        assert marker in validation
    assert "calculateSupplierObligationTotals(supplierObligationInputs)" in validation
    assert "calculateSupplierInvoicePriceVariance(invoice.toPriceVarianceInput())" in validation
