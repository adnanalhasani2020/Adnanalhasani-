package com.adnanalhasani.commercial

import java.util.Locale

internal fun CommercialCloudSupplierInvoiceSummary.toPriceVarianceInput() = SupplierInvoicePriceVarianceInput(
    currencyCode = currencyCode,
    subtotalMinor = subtotalMinor,
    taxMinor = taxMinor,
    freightMinor = freightMinor,
    totalMinor = totalMinor,
    lines = lines.map { line ->
        SupplierInvoicePriceVarianceLineInput(
            quantity = line.quantity,
            unitPriceMinor = line.unitPriceMinor,
            lineTotalMinor = line.lineTotalMinor,
            purchaseOrderUnitCostMinor = line.purchaseOrderUnitCostMinor
        )
    }
)

internal fun CommercialCloudSaleDocument.toCostProfitInput() = CostProfitSaleInput(
    currencyCode = currencyCode,
    subtotalMinor = subtotalMinor,
    discountMinor = discountMinor,
    lines = lines.map { CostProfitSaleLineInput(it.costTotalMinor) }
)

internal fun CommercialCloudSaleReturn.toCostProfitInput() = CostProfitReturnInput(
    currencyCode = currencyCode,
    approvedRefundMinor = approvedRefundMinor,
    items = items.map { item ->
        CostProfitReturnItemInput(
            saleItemCostTotalMinor = item.saleItemCostTotalMinor,
            restockedQuantity = item.restockedQuantity,
            saleItemQuantityInStockUnits = item.saleItemQuantityInStockUnits
        )
    }
)

internal fun List<CommercialCloudSaleDocument>.toCustomerStatementInputs(): List<CustomerStatementInvoiceInput> =
    filter { it.saleStatus.equals("completed", true) && it.customerId.isNotBlank() }.map { doc ->
        CustomerStatementInvoiceInput(
            customerId = doc.customerId,
            receiptNumber = doc.receiptNumber,
            customerName = doc.customerName,
            totalMinor = doc.totalMinor,
            currencyCode = doc.currencyCode,
            createdAt = doc.createdAt,
            payments = doc.payments.map { payment ->
                CustomerStatementPaymentInput(payment.id, payment.amountMinor, payment.status, payment.createdAt)
            },
            returns = doc.returns.map { saleReturn ->
                CustomerStatementReturnInput(
                    saleReturn.id,
                    saleReturn.status,
                    saleReturn.refundStatus,
                    saleReturn.approvedRefundMinor,
                    saleReturn.updatedAt
                )
            }
        )
    }

internal fun reportLineAmount(row: ReportLine, cloudMode: Boolean): String = row.displayAmount ?: if (row.currency.isBlank() || row.currency == "-") row.amount.toString() else reportMoney(row.amount, row.currency, cloudMode)

internal fun SupplierDueDateAgingReportState.toReportLines(asOfDate: String): List<ReportLine> =
    rows.map { aging ->
        ReportLine(
            asOfDate,
            aging.bucket,
            aging.supplierName,
            aging.balanceMinor,
            aging.currencyCode,
            "${aging.invoiceCount} فاتورة مفتوحة · الرصيد الحالي · الشرائح محسوبة من تاريخ الاستحقاق التعاقدي المحفوظ"
        )
    }
internal data class ReportSupplierMovement(val date: String, val supplierId: String, val supplier: String, val currency: String, val reference: String, val amount: Long, val description: String)


/** Maps saved cloud sales into item-level profitability inputs without owning calculations. */
internal fun List<CommercialCloudSaleDocument>.toItemProfitSaleLineInputs(
    startMillis: Long,
    endExclusive: Long
): List<ItemProfitSaleLineInput> =
    filter { it.saleStatus.equals("completed", true) }.flatMap { document ->
        val inPeriod = reportDateMillis(document.createdAt)?.let {
            it >= startMillis && it < endExclusive
        } == true
        document.lines.map { line ->
            ItemProfitSaleLineInput(
                saleItemId = line.id,
                saleId = document.id,
                productName = line.productName,
                currencyCode = document.currencyCode,
                saleTaxMinor = document.taxMinor,
                saleDiscountMinor = document.discountMinor,
                quantity = line.quantity,
                lineTotalMinor = line.lineTotalMinor,
                costTotalMinor = line.costTotalMinor,
                inSelectedPeriod = inPeriod
            )
        }
    }

/** Maps completed returns in the selected period to the profitability calculation contract. */
internal fun List<CommercialCloudSaleReturn>.toItemProfitReturnInputs(
    startMillis: Long,
    endExclusive: Long
): List<ItemProfitReturnInput> =
    filter { saleReturn ->
        saleReturn.status.equals("completed", true) &&
            reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
    }.map { saleReturn ->
        ItemProfitReturnInput(
            returnId = saleReturn.id,
            saleId = saleReturn.saleId,
            currencyCode = saleReturn.currencyCode,
            approvedRefundMinor = saleReturn.approvedRefundMinor,
            items = saleReturn.items.map { item ->
                ItemProfitReturnLineInput(
                    saleItemId = item.saleItemId,
                    quantity = item.quantity,
                    refundAmountMinor = item.refundAmountMinor,
                    refundAmountPresent = item.refundAmountPresent,
                    restockRequested = item.restock,
                    restockFlagPresent = item.restockFlagPresent,
                    restockedQuantity = item.restockedQuantity,
                    restockedQuantityPresent = item.restockedQuantityPresent,
                    saleItemQuantityInStockUnits = item.saleItemQuantityInStockUnits
                )
            }
        )
    }

/** Maps successful, in-period payments into the customer collection calculation contract. */
internal fun List<CommercialCloudSaleDocument>.toCustomerCollectionPaymentInputs(
    startMillis: Long,
    endExclusive: Long
): List<CustomerCollectionPaymentInput> =
    flatMap { document ->
        document.payments.filter { payment ->
            payment.status.equals("succeeded", true) &&
                reportDateMillis(payment.createdAt)?.let { it >= startMillis && it < endExclusive } == true
        }.map { payment ->
            CustomerCollectionPaymentInput(
                paymentId = payment.id,
                customerId = document.customerId,
                customerName = document.customerName,
                currencyCode = payment.currencyCode,
                amountMinor = payment.amountMinor
            )
        }
    }

/** Maps completed in-period sales into per-user performance calculation inputs. */
internal fun List<CommercialCloudSaleDocument>.toUserSalesPerformanceInputs(
    startMillis: Long,
    endExclusive: Long
): List<UserSalesPerformanceInvoiceInput> =
    filter { document ->
        document.saleStatus.equals("completed", true) &&
            reportDateMillis(document.createdAt)?.let { it >= startMillis && it < endExclusive } == true
    }.map { document ->
        UserSalesPerformanceInvoiceInput(
            invoiceId = document.id,
            userId = document.userId,
            currencyCode = document.currencyCode,
            totalMinor = document.totalMinor
        )
    }

/** Maps completed in-period sales into daily trend calculation inputs. */
internal fun List<CommercialCloudSaleDocument>.toDailySalesInvoiceInputs(
    startMillis: Long,
    endExclusive: Long
): List<DailySalesInvoiceInput> =
    filter { document ->
        document.saleStatus.equals("completed", true) &&
            reportDateMillis(document.createdAt)?.let { it >= startMillis && it < endExclusive } == true
    }.map { document ->
        DailySalesInvoiceInput(
            invoiceId = document.id,
            date = document.createdAt.take(10),
            currencyCode = document.currencyCode,
            totalMinor = document.totalMinor
        )
    }


/** Maps active supplier invoices in the selected period into summary calculation inputs. */
internal fun List<CommercialCloudPurchaseDocument>.toSupplierPurchaseInvoiceInputs(
    startMillis: Long,
    endExclusive: Long
): List<SupplierPurchaseInvoiceInput> =
    flatMap { purchase ->
        purchase.supplierInvoices.filter { invoice ->
            invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
        }.map { invoice ->
            SupplierPurchaseInvoiceInput(
                invoiceId = invoice.id,
                supplierId = purchase.supplierId,
                supplierName = purchase.supplierName,
                currencyCode = invoice.currencyCode,
                totalMinor = invoice.totalMinor
            )
        }
    }


/** Selects non-cancelled supplier invoices; retained as the shared source for validation and calculations. */
internal fun List<CommercialCloudPurchaseDocument>.activeSupplierInvoiceCandidates():
    List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>> =
    flatMap { purchase ->
        purchase.supplierInvoices.filter { invoice ->
            invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
        }.map { invoice -> purchase to invoice }
    }

/** Selects active supplier obligations from the already filtered invoice candidates. */
internal fun List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>>.activeSupplierObligationCandidates():
    List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>> =
    filter { (_, invoice) ->
        invoice.obligation != null &&
            invoice.obligation.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
    }

/** Maps active obligations recorded before the selected period end to calculation inputs. */
internal fun List<Pair<CommercialCloudPurchaseDocument, CommercialCloudSupplierInvoiceSummary>>.toSupplierObligationInputs(
    endExclusive: Long
): List<SupplierObligationInput> =
    mapNotNull { (purchase, invoice) ->
        val timestamp = reportDateMillis(invoice.recordedAt) ?: return@mapNotNull null
        if (timestamp >= endExclusive) return@mapNotNull null
        val obligation = invoice.obligation ?: return@mapNotNull null
        SupplierObligationInput(
            supplierId = purchase.supplierId,
            supplierName = purchase.supplierName.ifBlank { "مورد غير محدد" },
            purchaseOrderCurrencyCode = purchase.currencyCode,
            invoiceCurrencyCode = invoice.currencyCode,
            obligationCurrencyCode = obligation.currencyCode,
            amountDueMinor = obligation.amountDueMinor,
            amountPaidMinor = obligation.amountPaidMinor
        )
    }
