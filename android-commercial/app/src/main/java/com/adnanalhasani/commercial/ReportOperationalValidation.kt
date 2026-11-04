package com.adnanalhasani.commercial

/** Fail-closed integrity checks for operational and derived sales reports. */
internal fun reportCustomerOperationalStatementValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>
): String = when {
    !cloudMode -> "كشف العميل التشغيلي يتطلب فواتير السحابة والتحصيلات والمرتجعات."
    salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
    runCatching {
        val movements = buildCustomerOperationalStatementMovements(cloudSalesDocuments.toCustomerStatementInputs())
        movements.groupBy { it.customerId to it.currency }.values.forEach { customerMoves ->
            val ordered = customerMoves.sortedWith(compareBy<ReportCustomerMovement> { it.date }.thenBy { it.reference })
            sumReportAmountsExactly(ordered.map { it.amount })
            var balance = 0L
            ordered.forEach { balance = Math.addExact(balance, it.amount) }
        }
    }.isFailure -> "توجد فاتورة أو دفعة أو مرتجع عميل بمبلغ/تاريخ غير صالح، أو تجاوزت مجاميع كشف العميل نطاق الأرقام الدقيق؛ تم حجب الكشف."
    else -> ""
}

internal fun reportCustomerCollectionsValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    calculationError: String
): String = when {
    !cloudMode -> "التحصيل حسب العميل يتطلب سجلات الدفعات السحابية."
    salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
    cloudSalesDocuments.any { document ->
        document.payments.any { payment ->
            payment.status.equals("succeeded", true) &&
                (reportDateMillis(payment.createdAt) == null || payment.amountMinor <= 0L ||
                    !payment.currencyCode.matches(Regex("^[A-Z]{3}$")))
        }
    } -> "توجد دفعة ناجحة بتاريخ أو مبلغ أو عملة غير صالحة؛ تم حجب تجميع التحصيل حسب العميل."
    else -> calculationError
}

internal fun reportUserSalesPerformanceValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    calculationError: String
): String = when {
    !cloudMode -> "أداء المبيعات حسب المستخدم يتطلب سجلات السحابة."
    salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
    cloudSalesDocuments.any { document ->
        document.saleStatus.equals("completed", true) &&
            (reportDateMillis(document.createdAt) == null ||
                !document.currencyCode.matches(Regex("^[A-Z]{3}$")) || document.totalMinor < 0L)
    } -> "توجد فاتورة مكتملة بتاريخ أو عملة أو مبلغ غير صالح؛ تم حجب تقرير أداء المستخدمين."
    else -> calculationError
}

internal fun reportDailySalesTrendValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    calculationError: String
): String = when {
    !cloudMode -> "الاتجاه اليومي للمبيعات يتطلب سجلات السحابة."
    salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
    cloudSalesDocuments.any { document ->
        document.saleStatus.equals("completed", true) &&
            (reportDateMillis(document.createdAt) == null ||
                !document.currencyCode.matches(Regex("^[A-Z]{3}$")) || document.totalMinor < 0L)
    } -> "توجد فاتورة مكتملة بتاريخ أو عملة أو مبلغ غير صالح؛ تم حجب الاتجاه اليومي."
    else -> calculationError
}

internal fun reportExpenseValidationError(
    cloudMode: Boolean,
    expensesLoadError: String,
    cloudExpenses: List<CommercialCloudExpenseVoucher>,
    startMillis: Long,
    endExclusive: Long
): String = when {
    !cloudMode -> expensesLoadError
    expensesLoadError.isNotBlank() -> expensesLoadError
    cloudExpenses.any { expense ->
        expense.canceledAt.isBlank() &&
            reportDateMillis(expense.recordedAt)?.let { it >= startMillis && it < endExclusive } == true &&
            (expense.amountMinor <= 0L || !expense.currencyCode.matches(Regex("^[A-Z]{3}$")))
    } -> "توجد مصروفات مسجلة بمبلغ أو عملة غير صالحة؛ تم حجب التقرير بدل افتراض عملة أو إجمالي غير موثوق."
    else -> ""
}

internal fun reportPaymentMethodAnalysisValidationError(
    cloudMode: Boolean,
    paymentsLoadError: String,
    cloudPayments: List<CommercialCloudRecord>,
    startMillis: Long,
    endExclusive: Long
): String = when {
    !cloudMode -> "تحليل التحصيل حسب طريقة الدفع يتطلب سجل الدفعات السحابي."
    paymentsLoadError.isNotBlank() -> paymentsLoadError
    cloudPayments.any { row ->
        row.status.equals("succeeded", true) &&
            reportDateMillis(row.createdAt)?.let { it >= startMillis && it < endExclusive } == true &&
            (row.amountMinor <= 0L || !row.currencyCode.matches(Regex("^[A-Z]{3}$")))
    } -> "توجد دفعة ناجحة بمبلغ أو عملة غير صالحة؛ تم حجب التجميع حتى لا يظهر إجمالي ناقص."
    else -> ""
}
