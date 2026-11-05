package com.adnanalhasani.commercial

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Builds sales-period and invoice-detail rows from the existing local/cloud projections. */
internal fun buildSalesReportRows(
    selectedReport: String,
    cloudMode: Boolean,
    inPeriodCloudSalesDocuments: List<CommercialCloudSaleDocument>,
    inPeriodSales: List<SaleRecord>,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    sales: List<SaleRecord>,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    startDate: LocalDate?,
    endDate: LocalDate?,
    zone: ZoneId
): List<ReportLine>? = when (selectedReport) {
        "ملخص المبيعات حسب الفترة" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            val total = sumReportAmountsExactly(rows.map { it.totalMinor })
            ReportLine("${startText} — ${endText}", "ملخص", "إجمالي المبيعات المكتملة", total, currency, "${rows.size} فاتورة · متوسط ${reportMoney(if (rows.isEmpty()) 0L else total / rows.size, currency, true)}")
        } else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            ReportLine("${startText} — ${endText}", "ملخص", "إجمالي المبيعات المكتملة", sumReportAmountsExactly(rows.map { it.total.toLong() }), currency, "${rows.size} فاتورة · متوسط ${if (rows.isEmpty()) 0L else sumReportAmountsExactly(rows.map { it.total.toLong() }) / rows.size}")
        }
        "تفاصيل فواتير المبيعات" -> if (cloudMode) cloudSalesDocuments.filter { doc ->
            val timestamp = reportDateMillis(doc.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.sortedByDescending { it.createdAt }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName.ifBlank { "عميل غير محدد" },
                doc.totalMinor, doc.currencyCode, "الفاتورة: ${doc.saleStatus} · السداد: ${doc.paymentStatus} · بنود: ${doc.lines.size}")
        } else inPeriodSales.sortedByDescending { it.createdAt }.map {
            ReportLine(Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate().toString(), "فاتورة #${it.number}", it.product, it.total.toLong(), it.currencyCode, if (it.credit) "آجل" else "نقدي")
        }
        "تفاصيل بنود فواتير المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.flatMap { doc ->
            if (doc.lines.isEmpty()) listOf(ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.totalMinor, doc.currencyCode, "لا توجد بنود متاحة لهذه الفاتورة"))
            else doc.lines.mapIndexed { index, line ->
                ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber} · ${index + 1}", line.productName, line.lineTotalMinor, doc.currencyCode,
                    "العميل: ${doc.customerName} · الكمية: ${line.quantity} · سعر الوحدة: ${reportMoney(line.unitPriceMinor, doc.currencyCode, true)} · السداد: ${doc.paymentStatus}")
            }
        }.sortedByDescending { it.date }
        "المبيعات حسب الصنف" -> if (cloudMode) cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.flatMap { doc -> doc.lines.map { line -> Triple(line.productName, doc.currencyCode, line) } }
            .groupBy { it.first to it.second }.map { (key, rows) ->
                ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.third.lineTotalMinor }), key.second,
                    "${rows.fold(java.math.BigDecimal.ZERO) { total, row ->
                        val quantity = row.third.quantity.toBigDecimalOrNull()
                            ?: throw IllegalArgumentException("توجد كمية غير صالحة في تقرير المبيعات.")
                        require(quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 0) {
                            "تقرير المبيعات يحتوي كمية كسرية أو غير موجبة."
                        }
                        total.add(quantity)
                    }} وحدة · بنود فعلية")
            }.sortedByDescending { it.amount }
        else inPeriodSales.groupBy { it.product to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.total.toLong() }), key.second, "${sumReportCountsExactly(rows.map { it.quantity })} وحدة · ${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "الخصومات على فواتير المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.filter { it.discountMinor > 0L }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.discountMinor, doc.currencyCode, "خصم مسجل على الفاتورة")
        }.sortedByDescending { it.date }
        "الضرائب المسجلة على المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.filter { it.taxMinor > 0L }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.taxMinor, doc.currencyCode, "ضريبة مسجلة على الفاتورة")
        }.sortedByDescending { it.date }
        "المبيعات حسب العميل" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.customerName.ifBlank { "عميل غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.totalMinor }), key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount } else inPeriodSales.groupBy { it.customer.ifBlank { "عميل غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.total.toLong() }), key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "المبيعات حسب حالة السداد" -> inPeriodCloudSalesDocuments.groupBy { it.paymentStatus.ifBlank { "غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.totalMinor }), key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "المبيعات النقدية والآجلة" -> inPeriodSales.groupBy { (if (it.credit) "آجل" else "نقدي") to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, sumReportAmountsExactly(rows.map { it.total.toLong() }), key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "متوسط قيمة الفاتورة" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            val total = sumReportAmountsExactly(rows.map { it.totalMinor })
            ReportLine("-", "-", "متوسط قيمة الفاتورة", if (rows.isEmpty()) 0L else total / rows.size, currency, "${rows.size} فاتورة · الإجمالي ${reportMoney(total, currency, true)}")
        } else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            ReportLine("-", "-", "متوسط قيمة الفاتورة", if (rows.isEmpty()) 0L else sumReportAmountsExactly(rows.map { it.total.toLong() }) / rows.size, currency, "${rows.size} فاتورة · إجمالي ${sumReportAmountsExactly(rows.map { it.total.toLong() })}")
        }
        "مقارنة فترتين", "مقارنة الفترات" -> {
            val days = if (startDate != null && endDate != null) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1 else 0
            val previousStart = startDate?.minusDays(days.toLong())
            val previousEnd = startDate?.minusDays(1)
            if (cloudMode) {
                val previousCloudRows = if (previousStart != null && previousEnd != null) cloudSalesDocuments.filter { doc ->
                    doc.saleStatus.equals("completed", true) &&
                        (reportDateMillis(doc.createdAt)?.let { it >= previousStart.atStartOfDay(zone).toInstant().toEpochMilli() && it < previousEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() } == true)
                } else emptyList()
                (inPeriodCloudSalesDocuments.map { it.currencyCode.ifBlank { "غير محددة" } } + previousCloudRows.map { it.currencyCode.ifBlank { "غير محددة" } }).distinct().sorted().map { currency ->
                    val currentTotal = sumReportAmountsExactly(inPeriodCloudSalesDocuments.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.map { it.totalMinor })
                    val previousTotal = sumReportAmountsExactly(previousCloudRows.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.map { it.totalMinor })
                    ReportLine("${startText} — ${endText}", "مقارنة", "مبيعات الفترة الحالية", currentTotal, currency,
                        "الفترة السابقة: ${reportMoney(previousTotal, currency, true)} · الفرق: ${reportMoney(Math.subtractExact(currentTotal, previousTotal), currency, true)}")
                }
            } else {
                val previousRows = if (previousStart != null && previousEnd != null) sales.filter {
                    it.createdAt >= previousStart.atStartOfDay(zone).toInstant().toEpochMilli() &&
                        it.createdAt < previousEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                        it.status.equals("completed", true)
                } else emptyList()
                (inPeriodSales.map { it.currencyCode.ifBlank { "غير محددة" } } + previousRows.map { it.currencyCode.ifBlank { "غير محددة" } }).distinct().sorted().map { currency ->
                    val currentTotal = sumReportAmountsExactly(inPeriodSales.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.map { it.total.toLong() })
                    val previousTotal = sumReportAmountsExactly(previousRows.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.map { it.total.toLong() })
                    ReportLine("${startText} — ${endText}", "مقارنة", "مبيعات الفترة الحالية", currentTotal, currency, "الفترة السابقة: $previousTotal · الفرق: ${Math.subtractExact(currentTotal, previousTotal)}")
                }
            }
        }
        else -> null
}
