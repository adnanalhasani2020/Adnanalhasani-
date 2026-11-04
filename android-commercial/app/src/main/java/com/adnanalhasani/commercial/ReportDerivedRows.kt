package com.adnanalhasani.commercial

internal fun buildUserSalesPerformanceRows(
    calculation: Result<List<UserSalesPerformanceTotal>>,
    error: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else calculation.getOrThrow().map { row ->
        val actorLabel = if (row.userId == "غير منسوب") "مبيعات غير منسوبة لمستخدم"
            else "مستخدم ${row.userId}"
        ReportLine(
            "-",
            row.userId,
            actorLabel,
            row.totalMinor,
            row.currencyCode,
            "${row.invoiceCount} فاتورة مكتملة · متوسط الفاتورة ${reportMoney(row.averageInvoiceMinor, row.currencyCode, true)}"
        )
    }

internal fun buildDailySalesTrendRows(
    calculation: Result<List<DailySalesTrendTotal>>,
    error: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else calculation.getOrThrow().map { day ->
        ReportLine(
            day.date,
            "${day.invoiceCount} فاتورة",
            "إجمالي مبيعات اليوم",
            day.totalMinor,
            day.currencyCode,
            "إجمالي الفواتير المكتملة في هذا اليوم · العملة: ${day.currencyCode}"
        )
    }

internal fun buildCustomerReceivableAgingRows(
    report: CustomerReceivableAgingReportState,
    error: String,
    asOfDate: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else report.rows.map { aging ->
        ReportLine(
            asOfDate,
            aging.bucket,
            aging.customerName,
            aging.balanceMinor,
            aging.currencyCode,
            "${aging.invoiceCount} فاتورة مفتوحة · الرصيد الحالي بعد المرتجعات والاستردادات · العمر من تاريخ الفاتورة وليس الاستحقاق"
        )
    }

internal fun buildCustomerCollectionsRows(
    calculation: Result<List<CustomerCollectionSummary>>,
    error: String,
    startText: String,
    endText: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else calculation.getOrThrow().map { customer ->
        ReportLine(
            "${startText} — ${endText}",
            "${customer.paymentCount} دفعة",
            customer.customerName,
            customer.totalMinor,
            customer.currencyCode,
            "دفعات ناجحة فقط · ${if (customer.customerId.isBlank()) "لا يوجد معرف عميل محفوظ" else "معرف العميل: ${customer.customerId}"}"
        )
    }

internal fun buildSupplierPurchaseSummaryRows(
    calculation: Result<List<SupplierPurchaseSummary>>,
    error: String,
    startText: String,
    endText: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else calculation.getOrThrow().map { summary ->
        ReportLine(
            "${startText} — ${endText}",
            "${summary.invoiceCount} فاتورة",
            summary.supplierName,
            summary.totalMinor,
            summary.currencyCode,
            "إجمالي فواتير المورد غير الملغاة · معرف المورد: ${summary.supplierId}"
        )
    }

internal fun buildSupplierLiabilityAgingRows(
    report: SupplierLiabilityAgingReportState,
    error: String,
    asOfDate: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else report.rows.map { aging ->
        ReportLine(
            asOfDate,
            aging.bucket,
            aging.supplierName,
            aging.balanceMinor,
            aging.currencyCode,
            "${aging.invoiceCount} فاتورة مفتوحة · الرصيد الحالي حتى اليوم · العمر من تاريخ تسجيل الفاتورة وليس من تاريخ استحقاق تعاقدي"
        )
    }

internal fun buildSupplierDueDateAgingRows(
    report: SupplierDueDateAgingReportState,
    error: String,
    asOfDate: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else report.toReportLines(asOfDate)
