package com.adnanalhasani.commercial

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
