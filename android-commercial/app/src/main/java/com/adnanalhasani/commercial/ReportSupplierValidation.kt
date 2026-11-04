package com.adnanalhasani.commercial

import java.util.Locale

/** Fail-closed source and record-integrity checks for supplier invoice reports. */
internal fun reportSupplierInvoiceVarianceError(
    cloudMode: Boolean,
    purchasesLoadError: String,
    cloudPurchases: List<CommercialCloudPurchaseDocument>,
    startMillis: Long,
    endExclusive: Long
): String = when {
    !cloudMode -> "فروقات أسعار فواتير الموردين تتطلب فواتير السحابة وبنودها المحفوظة."
    purchasesLoadError.isNotBlank() -> purchasesLoadError
    cloudPurchases.any { purchase ->
        purchase.supplierInvoices.filter { invoice ->
            invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
        }.any { invoice ->
            invoice.currencyCode != purchase.currencyCode ||
                runCatching { calculateSupplierInvoicePriceVariance(invoice.toPriceVarianceInput()) }.isFailure
        }
    } -> "تعذر مطابقة أحد فواتير المورد مع بنودها وأسعار أمر الشراء؛ لم يتم عرض إجمالي جزئي أو فرق سعر مضلل."
    else -> ""
}

internal fun reportSupplierInvoiceDetailValidationError(
    cloudMode: Boolean,
    purchasesLoadError: String,
    activeSupplierInvoiceCandidates: List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>>
): String = when {
    !cloudMode -> "تفاصيل فواتير الشراء تتطلب مصادر السحابة."
    purchasesLoadError.isNotBlank() -> purchasesLoadError
    activeSupplierInvoiceCandidates.any { (_, invoice) -> reportDateMillis(invoice.recordedAt) == null } ->
        "توجد فاتورة مورد بتاريخ تسجيل غير صالح؛ تعذر تحديد الفترة بدقة."
    activeSupplierInvoiceCandidates.any { (purchase, invoice) ->
        val obligation = invoice.obligation
        invoice.totalMinor < 0L || !purchase.currencyCode.matches(Regex("^[A-Z]{3}$")) ||
            invoice.currencyCode != purchase.currencyCode ||
            (obligation != null && (obligation.currencyCode != purchase.currencyCode ||
                obligation.amountDueMinor < 0L || obligation.amountPaidMinor < 0L))
    } -> "توجد فاتورة أو مستحق مورد بمبلغ أو عملة غير متطابقة؛ تم حجب التقرير."
    else -> ""
}

internal fun reportSupplierPurchaseSummaryValidationError(
    cloudMode: Boolean,
    purchasesLoadError: String,
    supplierInvoiceDetailError: String,
    activeSupplierInvoiceCandidates: List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>>,
    startMillis: Long,
    endExclusive: Long,
    calculationError: String
): String = when {
    !cloudMode -> "ملخص المشتريات حسب المورد يتطلب فواتير السحابة."
    purchasesLoadError.isNotBlank() -> purchasesLoadError
    supplierInvoiceDetailError.isNotBlank() -> supplierInvoiceDetailError
    activeSupplierInvoiceCandidates.any { (_, invoice) ->
        reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true &&
            (invoice.subtotalMinor < 0L || invoice.taxMinor < 0L || invoice.freightMinor < 0L ||
                invoice.lines.isEmpty() ||
                invoice.lines.any { line ->
                    line.lineTotalMinor < 0L || line.unitPriceMinor < 0L ||
                        line.quantity.toBigDecimalOrNull()?.signum() != 1
                } ||
                runCatching { sumReportAmountsExactly(invoice.lines.map { it.lineTotalMinor }) }.getOrNull() != invoice.subtotalMinor ||
                runCatching {
                    Math.addExact(Math.addExact(invoice.subtotalMinor, invoice.taxMinor), invoice.freightMinor)
                }.getOrNull() != invoice.totalMinor)
    } -> "توجد فاتورة مورد لا تتطابق بنودها أو إجمالياتها مع الضريبة والشحن؛ تم حجب ملخص الموردين."
    else -> calculationError
}

internal fun reportSupplierObligationsValidationError(
    cloudMode: Boolean,
    purchasesLoadError: String,
    supplierObligationCandidates: List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>>,
    supplierObligationInputs: List<SupplierObligationInput>
): String = when {
    !cloudMode -> "مستحقات فواتير الموردين تتطلب مصادر السحابة."
    purchasesLoadError.isNotBlank() -> purchasesLoadError
    supplierObligationCandidates.any { (_, invoice) -> reportDateMillis(invoice.recordedAt) == null } ->
        "توجد فاتورة التزام مورد بتاريخ غير صالح؛ لا يمكن حساب الرصيد حتى نهاية الفترة."
    runCatching { calculateSupplierObligationTotals(supplierObligationInputs) }.isFailure ->
        "تعذر مطابقة عملات الالتزامات أو تجاوزت مجاميعها نطاق الأرقام الدقيق؛ تم حجب المستحقات."
    else -> ""
}


/** Preflights supplier invoice/payment movement negation, opening totals, and running balances. */
internal fun reportSupplierMovementValidationError(
    purchasesLoadError: String,
    supplierPaymentsLoadError: String,
    movements: List<ReportSupplierMovement>,
    startMillis: Long,
    endExclusive: Long
): String = when {
    purchasesLoadError.isNotBlank() -> purchasesLoadError
    supplierPaymentsLoadError.isNotBlank() -> supplierPaymentsLoadError
    movements.any { it.amount == Long.MIN_VALUE } ->
        "توجد دفعة مورد تتجاوز نطاق الأرقام الدقيق عند عكس اتجاهها؛ تم حجب كشف الحركات."
    runCatching {
        movements.groupBy { Triple(it.supplierId, it.supplier, it.currency) }.values.forEach { supplierMoves ->
            val ordered = supplierMoves.sortedWith(
                compareBy<ReportSupplierMovement> { it.date }.thenBy { it.reference }
            )
            val opening = sumReportAmountsExactly(
                ordered.filter { reportDateMillis(it.date)?.let { timestamp -> timestamp < startMillis } == true }
                    .map { it.amount }
            )
            var balance = opening
            ordered.filter {
                reportDateMillis(it.date)?.let { timestamp ->
                    timestamp >= startMillis && timestamp < endExclusive
                } == true
            }.forEach { movement ->
                balance = Math.addExact(balance, movement.amount)
            }
        }
    }.isFailure -> "تجاوزت حركات أو أرصدة كشف الموردين نطاق الأرقام الدقيق؛ تم حجب كشف الحركات."
    else -> ""
}
