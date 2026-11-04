package com.adnanalhasani.commercial

import java.time.LocalDate
import java.util.Locale

/** Pure row assembly for ledger cash-flow and tax/discount summaries. */
internal fun buildCashFlowReportLines(
    cloudLedgerLines: List<CommercialCloudJournalLine>,
    cloudGlMappings: List<CommercialCloudGlAccountMapping>,
    startDate: LocalDate?,
    endDate: LocalDate?,
    startText: String,
    endText: String
): List<ReportLine> {
    val cashKeys = setOf("cash", "bank", "card_clearing", "other_receipts", "check_clearing", "other_disbursements")
            val cashAccountIds = cloudGlMappings.filter { it.mappingKey in cashKeys }.map { it.ledgerAccountId }.toSet()
            val movements = cloudLedgerLines.filter { it.accountId in cashAccountIds }
                .mapNotNull { line ->
                    val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull() ?: return@mapNotNull null
                    Triple(line, date, Math.subtractExact(line.debitMinor, line.creditMinor))
                }.sortedWith(compareBy<Triple<CommercialCloudJournalLine, LocalDate, Long>> { it.second }.thenBy { it.first.entryNumber }.thenBy { it.first.lineNumber })
            val currencies = movements.map { it.first.currencyCode }.filter { it.matches(Regex("^[A-Z]{3}$")) }.distinct().sorted()
    return buildList {
                currencies.forEach { currency ->
                    val currencyMoves = movements.filter { it.first.currencyCode == currency }
                    currencyMoves.groupBy { it.first.accountId to it.first.accountCode }.toSortedMap(compareBy<Pair<String, String>> { it.second }.thenBy { it.first })
                        .forEach { (accountKey, accountMoves) ->
                            val opening = sumReportAmountsExactly(accountMoves.filter { it.second < (startDate ?: LocalDate.MIN) }.map { it.third })
                            val periodMoves = accountMoves.filter { startDate != null && endDate != null && !it.second.isBefore(startDate) && !it.second.isAfter(endDate) }
                            var balance = opening
                            val accountName = accountMoves.firstOrNull()?.first?.accountName?.ifBlank { accountKey.second } ?: accountKey.second
                            add(ReportLine(startText, "رصيد افتتاحي", accountName, opening, currency, "حساب النقد/البنك ${accountKey.second} · رصيد من القيود المرحلة قبل الفترة"))
                            periodMoves.forEach { (line, date, amount) ->
                                balance = Math.addExact(balance, amount)
                                add(ReportLine(date.toString(), "قيد ${line.entryNumber} · سطر ${line.lineNumber}", accountName, amount, currency,
                                    "${line.entryDescription} · ${line.sourceType}:${line.sourceId} · الحساب ${line.accountCode} · الرصيد المتحرك ${reportMoney(balance, currency, true)}"))
                            }
                            val debitMovements = sumReportAmountsExactly(periodMoves.mapNotNull { it.third.takeIf { amount -> amount > 0L } })
                            val creditMovements = sumReportAmountsExactly(periodMoves.mapNotNull { it.third.takeIf { amount -> amount < 0L }?.let(Math::negateExact) })
                            add(ReportLine("${startText} — ${endText}", "إجمالي الحركة المدينة", accountName, debitMovements, currency,
                                "إجمالي الزيادات المدينة على حساب النقد/البنك خلال الفترة؛ قد تشمل تحويلات داخلية"))
                            add(ReportLine("${startText} — ${endText}", "إجمالي الحركة الدائنة", accountName, -creditMovements, currency,
                                "إجمالي التخفيضات الدائنة على حساب النقد/البنك خلال الفترة؛ قد تشمل تحويلات داخلية"))
                            add(ReportLine(endText, "رصيد ختامي", accountName, balance, currency, "الرصيد الافتتاحي + الحركة المرحلة · حساب ${accountKey.second}"))
                        }
                }
            }
}

internal data class TaxDiscountSummaryReportState(val lines: List<ReportLine>, val error: String)

internal fun buildTaxDiscountSummaryReportState(
    sales: List<CommercialCloudSaleDocument>,
    purchases: List<CommercialCloudPurchaseDocument>,
    returns: List<CommercialCloudSaleReturn>,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    sourceError: String
): TaxDiscountSummaryReportState {
    if (sourceError.isNotBlank()) return TaxDiscountSummaryReportState(emptyList(), sourceError)
    val totals = runCatching {
        calculateTaxDiscountSummaryTotals(buildList {
            sales.filter { doc ->
                doc.saleStatus.equals("completed", true) &&
                    reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }.forEach { doc ->
                if (doc.discountMinor > 0L) add(TaxDiscountSummaryInput(doc.id, doc.currencyCode, "sales_discount", doc.discountMinor))
                if (doc.taxMinor > 0L) add(TaxDiscountSummaryInput(doc.id, doc.currencyCode, "sales_tax", doc.taxMinor))
            }
            purchases.forEach { purchase ->
                purchase.supplierInvoices.filter { invoice ->
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
                }.forEach { invoice ->
                    if (invoice.taxMinor > 0L) add(TaxDiscountSummaryInput(
                        invoice.id, invoice.currencyCode, "purchase_tax", invoice.taxMinor
                    ))
                }
            }
            returns.filter { saleReturn ->
                saleReturn.status.equals("completed", true) &&
                    reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
            }.forEach { saleReturn ->
                val refund = saleReturn.approvedRefundMinor ?: 0L
                if (refund > 0L) add(TaxDiscountSummaryInput(
                    saleReturn.id, saleReturn.currencyCode, "sales_return", refund
                ))
            }
        })
    }.getOrElse {
        return TaxDiscountSummaryReportState(emptyList(), "تعذر تجميع الضرائب والخصومات بدقة؛ تم حجب الملخص بدل عرض أرقام ناقصة.")
    }
    val period = startText + " — " + endText
    val lines = if (totals.isEmpty()) {
        listOf(ReportLine(period, "ملخص", "لا توجد ضرائب أو خصومات أو مرتجعات مسجلة ضمن الفترة", 0L, "—",
            "لا توجد مستندات بمبالغ مسجلة؛ لم يتم استنتاج أي قيمة ضريبية."))
    } else buildList {
        totals.forEach { total ->
            val currency = total.currencyCode
            add(ReportLine(period, "خصومات المبيعات", "إجمالي الخصومات الممنوحة",
                total.salesDiscountMinor, currency, "عدد فواتير البيع ذات الخصم: " + total.salesDiscountDocumentCount))
            add(ReportLine(period, "ضريبة المبيعات", "إجمالي الضريبة المسجلة على المبيعات",
                total.salesTaxMinor, currency, "عدد فواتير البيع ذات الضريبة: " + total.salesTaxDocumentCount))
            add(ReportLine(period, "ضريبة المشتريات", "إجمالي الضريبة المسجلة على المشتريات",
                total.purchaseTaxMinor, currency, "عدد فواتير الموردين ذات الضريبة: " + total.purchaseTaxDocumentCount))
            add(ReportLine(period, "مرتجعات المبيعات", "قيمة المرتجعات المعتمدة",
                total.salesReturnMinor, currency, "عدد المرتجعات المعتمدة: " + total.salesReturnCount + "؛ لا تمثل هذه القيمة ضريبة مستردة."))
        }
    }
    return TaxDiscountSummaryReportState(lines, "")
}

