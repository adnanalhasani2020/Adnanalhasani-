package com.adnanalhasani.commercial

internal fun buildItemProfitabilityRows(
    calculation: Result<List<ItemProfitabilityTotals>>,
    error: String,
    startText: String,
    endText: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else calculation.getOrThrow().map { item ->
        ReportLine(
            "${startText} — ${endText}",
            "ربحية صنف",
            item.productName,
            item.grossProfitMinor,
            item.currencyCode,
            "الإيراد: ${reportMoney(item.salesMinor, item.currencyCode, true)} · المرتجعات المعتمدة: ${reportMoney(item.refundMinor, item.currencyCode, true)} · صافي الإيراد: ${reportMoney(item.netRevenueMinor, item.currencyCode, true)} · تكلفة البيع: ${reportMoney(item.soldCostMinor, item.currencyCode, true)} · تكلفة المرتجع المعاد تخزينه: ${reportMoney(item.returnedCostMinor, item.currencyCode, true)} · صافي التكلفة: ${reportMoney(item.netCostMinor, item.currencyCode, true)} · عدد بنود البيع: ${item.saleLineCount}",
            displayAmount = "الكمية المباعة: ${item.soldQuantity.stripTrailingZeros().toPlainString()} · الكمية المرتجعة: ${item.returnedQuantity}"
        )
    }

internal fun buildCostProfitRows(
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    cloudReturns: List<CommercialCloudSaleReturn>,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    error: String
): List<ReportLine> =
    if (error.isNotBlank()) emptyList() else {
        val periodSales = cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) &&
                reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
        }
        val periodReturns = cloudReturns.filter { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
        }
        buildList {
            calculateCostProfitReportTotals(periodSales.map { it.toCostProfitInput() }, periodReturns.map { it.toCostProfitInput() }).forEach { totals ->
                val currency = totals.currencyCode
                add(ReportLine("${startText} — ${endText}", "إيرادات", "إجمالي المبيعات بعد الخصومات", totals.grossSalesMinor, currency,
                    "إجمالي ${totals.invoiceCount} فاتورة مكتملة؛ الإيراد لا يتضمن الضريبة"))
                if (totals.returnRevenueMinor != 0L) add(ReportLine("${startText} — ${endText}", "مرتجعات", "مبالغ المرتجعات المعتمدة", -totals.returnRevenueMinor, currency,
                    "مرتجعات مكتملة ضمن الفترة فقط؛ تم التحقق من ربط الفاتورة والعملة"))
                add(ReportLine("${startText} — ${endText}", "صافي الإيرادات", "صافي الإيرادات بعد المرتجعات", totals.netRevenueMinor, currency,
                    "من الفواتير المكتملة ناقص الخصومات والمرتجعات المعتمدة"))
                add(ReportLine("${startText} — ${endText}", "تكلفة البضاعة", "تكلفة البنود المباعة المحفوظة", -totals.soldCostMinor, currency,
                    "لقطات cost_total_minor المسجلة عند البيع؛ لا تستخدم تكلفة الشراء الحالية"))
                if (totals.returnedCostMinor != 0L) add(ReportLine("${startText} — ${endText}", "عكس تكلفة المرتجع", "تكلفة الكمية التي أعيد تخزينها", totals.returnedCostMinor, currency,
                    "محسوبة من تكلفة بند البيع التاريخية والكمية المعاد تخزينها"))
                add(ReportLine("${startText} — ${endText}", "تكلفة صافية", "تكلفة البضاعة بعد المرتجعات المعاد تخزينها", -totals.netCostMinor, currency,
                    "تكلفة البيع ناقص تكلفة المرتجعات التي أعيد تخزينها"))
                add(ReportLine("${startText} — ${endText}", "مجمل الربح", "مجمل الربح بعد الخصومات والمرتجعات", totals.grossProfitMinor, currency,
                    "إيرادات صافية ناقص تكلفة تاريخية صافية؛ لا يمثل صافي الربح بعد المصروفات التشغيلية"))
            }
        }
    }
