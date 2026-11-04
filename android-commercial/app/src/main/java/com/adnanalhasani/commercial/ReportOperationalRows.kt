package com.adnanalhasani.commercial

import java.time.Instant
import java.time.ZoneId

/** Builds payment, settlement, return, and expense operational report rows. */
internal fun buildOperationalReportRows(
    selectedReport: String,
    cloudMode: Boolean,
    cloudPayments: List<CommercialCloudRecord>,
    cloudReturns: List<CommercialCloudSaleReturn>,
    cloudExpenses: List<CommercialCloudExpenseVoucher>,
    inPeriodSettlements: List<SettlementRecord>,
    inPeriodExpenses: List<ExpenseRecord>,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    zone: ZoneId
): List<ReportLine>? = when (selectedReport) {
        "التحصيل حسب طريقة الدفع" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                row.status.equals("succeeded", true)
        }.groupBy { it.paymentMethodCode.ifBlank { "غير محددة" } to it.currencyCode }.map { (key, rows) ->
            val total = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
            ReportLine(
                "${startText} — ${endText}",
                "تجميع",
                key.first,
                total,
                key.second,
                "عدد الدفعات: ${rows.size} · دفعات ناجحة فقط · المصدر: commercial_payments"
            )
        }.sortedWith(compareBy<ReportLine> { it.currency }.thenBy { it.name })
        "الدفعات السحابية الناجحة" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                row.status.equals("succeeded", true)
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, row.amountMinor, row.currencyCode, row.status)
        }
        "التحصيلات المسجلة محليًا" -> inPeriodSettlements.map {
            ReportLine(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toString(), "فاتورة #${it.invoiceNumber}", it.customer, it.amount.toLong(), "YER", "تحصيل محلي")
        }
        "مرتجعات المبيعات وحالة التسوية" -> cloudReturns.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            val approved = if (row.status in setOf("approved", "completed")) (row.approvedRefundMinor ?: 0L) else 0L
            ReportLine(row.createdAt.take(10), row.receiptNumber, row.customerName, approved, row.currencyCode,
                "الطلب: ${row.status} · الاسترداد: ${row.refundStatus} · المخزون: ${row.restockStatus}")
        }.sortedByDescending { it.date }
        "سندات القبض والصرف" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, row.amountMinor, row.currencyCode, row.status)
        }.sortedByDescending { it.date }
        "ملخص المصروفات حسب التصنيف وطريقة الصرف" -> cloudExpenses.filter { expense ->
            expense.canceledAt.isBlank() &&
                reportDateMillis(expense.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
        }.groupBy { Triple(it.category, it.paymentMethod.ifBlank { "غير محددة" }, it.currencyCode) }
            .map { (key, rows) ->
                val total = rows.fold(0L) { sum, expense -> Math.addExact(sum, expense.amountMinor) }
                ReportLine(
                    "${startText} — ${endText}",
                    "تجميع",
                    key.first,
                    total,
                    key.third,
                    "طريقة الصرف: ${key.second} · عدد السندات: ${rows.size} · سندات غير ملغاة فقط"
                )
            }.sortedWith(compareBy<ReportLine> { it.currency }.thenBy { it.name })
        "المصروفات التشغيلية" -> (if (cloudMode) cloudExpenses.filter { it.canceledAt.isBlank() && (reportDateMillis(it.recordedAt)?.let { ts -> ts >= startMillis && ts < endExclusive } == true) }.map {
            ReportLine(it.recordedAt.take(10), it.id, it.category, it.amountMinor, it.currencyCode, it.paymentMethod)
        } else inPeriodExpenses.map {
            ReportLine(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toString(), "مصروف #${it.id}", it.category, it.amount.toLong(), "YER", it.note)
        })
        else -> null
}
