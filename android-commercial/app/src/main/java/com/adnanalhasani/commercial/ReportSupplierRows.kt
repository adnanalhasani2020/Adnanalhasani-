package com.adnanalhasani.commercial

import java.util.Locale

/** Builds supplier invoice, obligation, variance, and movement report rows. */
internal fun buildSupplierReportRows(
    selectedReport: String,
    cloudPurchases: List<CommercialCloudPurchaseDocument>,
    cloudSupplierPayments: List<CommercialCloudSupplierPaymentMovement>,
    supplierFilter: String,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    supplierObligationInputs: List<SupplierObligationInput>
): List<ReportLine>? = when (selectedReport) {
        "فروقات أسعار فواتير الموردين" -> cloudPurchases.flatMap { purchase ->
            purchase.supplierInvoices.filter { invoice ->
                invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                    reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
            }.map { invoice ->
                val variance = calculateSupplierInvoicePriceVariance(invoice.toPriceVarianceInput())
                val itemPreview = invoice.lines.take(4).joinToString("؛ ") { line ->
                    "${line.purchaseOrderItemDescription.ifBlank { "بند" }}: ${line.quantity} × ${reportMoney(line.unitPriceMinor, invoice.currencyCode, true)} (أساس ${reportMoney(line.purchaseOrderUnitCostMinor!!, invoice.currencyCode, true)})"
                }
                val moreItems = if (invoice.lines.size > 4) " · وبنود أخرى: ${invoice.lines.size - 4}" else ""
                ReportLine(
                    invoice.recordedAt.take(10),
                    "فاتورة المورد #${invoice.invoiceNumber.ifBlank { invoice.id }}",
                    purchase.supplierName,
                    variance.varianceMinor,
                    invoice.currencyCode,
                    "فرق موجب = أعلى من أمر الشراء، وسالب = أقل · أساس الأمر: ${reportMoney(variance.expectedSubtotalMinor, invoice.currencyCode, true)} · الفاتورة قبل الضريبة والشحن: ${reportMoney(variance.invoiceSubtotalMinor, invoice.currencyCode, true)} · الضريبة: ${reportMoney(invoice.taxMinor, invoice.currencyCode, true)} · الشحن: ${reportMoney(invoice.freightMinor, invoice.currencyCode, true)} · التفاصيل: ${itemPreview}${moreItems}"
                )
            }
        }.sortedByDescending { it.date }
        "فواتير الشراء والمستحقات المسجلة" -> cloudPurchases.flatMap { purchase ->
            purchase.supplierInvoices.filter { invoice ->
                val timestamp = reportDateMillis(invoice.recordedAt)
                timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
            }.map { invoice ->
                val due = invoice.obligation?.amountDueMinor
                val paid = invoice.obligation?.amountPaidMinor
                ReportLine(invoice.recordedAt.take(10), invoice.invoiceNumber.ifBlank { purchase.orderNumber }, purchase.supplierName,
                    invoice.totalMinor, invoice.currencyCode.ifBlank { purchase.currencyCode },
                    if (due != null && paid != null) {
                        val balance = Math.subtractExact(due, paid)
                        if (balance >= 0L) "المستحق: ${reportMoney(balance, invoice.currencyCode, true)} · ${invoice.state}"
                        else "رصيد دائن/مقدم للمورد: ${reportMoney(balance, invoice.currencyCode, true)} · ${invoice.state}"
                    } else invoice.state)
            }
        }.sortedByDescending { it.date }
        "مستحقات فواتير الموردين" -> calculateSupplierObligationTotals(supplierObligationInputs).map { totals ->
            val balanceLabel = if (totals.balanceMinor >= 0L) "المستحق للمورد" else "رصيد دائن/مقدم للمورد"
            ReportLine(
                endText,
                totals.supplierId,
                totals.supplierName,
                totals.balanceMinor,
                totals.currencyCode,
                "إجمالي الالتزامات: ${reportMoney(totals.amountDueMinor, totals.currencyCode, true)} · المسدد: ${reportMoney(totals.amountPaidMinor, totals.currencyCode, true)} · ${balanceLabel}: ${reportMoney(totals.balanceMinor, totals.currencyCode, true)}"
            )
        }.sortedByDescending { it.amount }
        "كشف حركات الموردين" -> {
            val invoiceMovements = cloudPurchases.flatMap { purchase ->
                purchase.supplierInvoices.filter { invoice ->
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        invoice.obligation != null && invoice.obligation.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        invoice.obligation.amountDueMinor > 0L && invoice.obligation.currencyCode.matches(Regex("^[A-Z]{3}$"))
                }.map { invoice ->
                    val obligation = invoice.obligation!!
                    ReportSupplierMovement(invoice.recordedAt.take(10), purchase.supplierId, purchase.supplierName, obligation.currencyCode,
                        "فاتورة مورد ${invoice.invoiceNumber.ifBlank { invoice.id }}", obligation.amountDueMinor, "مدين · التزام فاتورة مسجل")
                }
            }
            val paymentMovements = cloudSupplierPayments.map { payment ->
                ReportSupplierMovement(payment.recordedAt.take(10), payment.supplierId, payment.supplierName, payment.currencyCode,
                    "دفعة ${payment.id}", -payment.amountMinor, "دائن · سداد مرحل · فاتورة ${payment.invoiceNumber.ifBlank { payment.supplierInvoiceId }} · ${payment.paymentMethod}")
            }
            (invoiceMovements + paymentMovements).filter { movement -> supplierFilter.isBlank() || movement.supplier.contains(supplierFilter.trim(), true) }
                .groupBy { Triple(it.supplierId, it.supplier, it.currency) }.toSortedMap(compareBy<Triple<String, String, String>> { it.second }.thenBy { it.third }.thenBy { it.first }).flatMap { (key, supplierMoves) ->
                    val ordered = supplierMoves.sortedWith(compareBy<ReportSupplierMovement> { it.date }.thenBy { it.reference })
                    val opening = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts < startMillis } == true }.sumOf { it.amount }
                    val periodMoves = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts >= startMillis && ts < endExclusive } == true }
                    if (periodMoves.isEmpty() && opening == 0L) emptyList() else buildList {
                        var balance = opening
                        if (opening != 0L) add(ReportLine(startText, "افتتاحي", key.second, opening, key.third, "رصيد افتتاحي وفق الفواتير والدفعات المحملة · المورد: ${key.first}"))
                        periodMoves.forEach { movement ->
                            balance = Math.addExact(balance, movement.amount)
                            add(ReportLine(movement.date, movement.reference, movement.supplier, movement.amount, movement.currency, "${movement.description} · الرصيد الجزئي: ${reportMoney(balance, movement.currency, true)} · المورد: ${movement.supplierId}"))
                        }
                        add(ReportLine(endText, "ختامي", key.second, balance, key.third, "رصيد جزئي للفواتير والدفعات فقط؛ لا يشمل مرتجعات أو تسويات غير محملة · المورد: ${key.first}"))
                    }
                }
        }
        else -> null
}
