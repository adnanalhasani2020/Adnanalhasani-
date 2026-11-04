package com.adnanalhasani.commercial

/** Validates historical cost/profit source integrity before exposing calculated totals. */
internal fun reportCostProfitValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    returnsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    cloudReturns: List<CommercialCloudSaleReturn>,
    startMillis: Long,
    endExclusive: Long
): String = when {
    !cloudMode -> "تكلفة الربح التاريخية تتطلب سجلات تكلفة السحابة."
    salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
    returnsLoadError.isNotBlank() -> returnsLoadError
    cloudSalesDocuments.any { doc ->
        doc.saleStatus.equals("completed", true) &&
            reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true &&
            (doc.lines.isEmpty() || doc.lines.any { it.costTotalMinor == null || it.costTotalMinor < 0L })
    } -> "توجد بنود مبيعات في الفترة بلا تكلفة تاريخية مسجلة؛ لا يمكن اعتماد الربح."
    cloudReturns.any { saleReturn ->
        saleReturn.status.equals("completed", true) &&
            reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
            (saleReturn.approvedRefundMinor == null ||
                saleReturn.items.any { it.restockedQuantity > 0 && (it.saleItemCostTotalMinor == null || it.saleItemQuantityInStockUnits <= 0L) })
    } -> "توجد مرتجعات مكتملة تفتقد مبلغ اعتماد أو تكلفة البند التاريخية اللازمة لتسوية الربح."
    cloudReturns.any { saleReturn ->
        saleReturn.status.equals("completed", true) &&
            reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
            cloudSalesDocuments.none { it.id == saleReturn.saleId && it.currencyCode == saleReturn.currencyCode }
    } -> "تعذر مطابقة عملة أو فاتورة أحد المرتجعات مع سجل البيع الأصلي؛ تم حجب الربح."
    cloudReturns.any { saleReturn ->
        saleReturn.status.equals("completed", true) &&
            reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
            cloudSalesDocuments.any { it.id == saleReturn.saleId && it.taxMinor > 0L }
    } -> "توجد مرتجعات لفواتير ضريبية؛ لم تُفصل حصة الضريبة في مبلغ الاسترداد، لذلك حُجب صافي الربح."
    runCatching {
        val periodSales = cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) &&
                reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
        }
        val periodReturns = cloudReturns.filter { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
        }
        calculateCostProfitReportTotals(periodSales.map { it.toCostProfitInput() }, periodReturns.map { it.toCostProfitInput() })
    }.isFailure -> "تجاوزت مجاميع المبيعات أو التكلفة أو المرتجعات نطاق الأرقام الدقيق، أو تعذر تسويتها؛ تم حجب الربح بدل إظهار إجمالي ملتف أو ناقص."
    else -> ""
}
