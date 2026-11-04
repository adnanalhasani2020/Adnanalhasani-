package com.adnanalhasani.commercial

import java.time.LocalDate

/** Builds customer and supplier party-statement rows from loaded party/ledger projections. */
internal fun buildPartyStatementReportRows(
    selectedReport: String,
    startText: String,
    endText: String,
    customerFilter: String,
    supplierFilter: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    cloudPurchases: List<CommercialCloudPurchaseDocument>,
    cloudLedgerLines: List<CommercialCloudJournalLine>,
    cloudSupplierControlAccounts: List<CommercialCloudSupplierControlAccount>,
    cloudGlMappings: List<CommercialCloudGlAccountMapping>,
    cloudCustomerPartyAccounts: List<CommercialCloudPartyAccountLink>,
    cloudSupplierPartyAccounts: List<CommercialCloudPartyAccountLink>,
    startDate: LocalDate?,
    endDate: LocalDate?,
    startMillis: Long,
    endExclusive: Long,
    customerStatementError: String,
    supplierStatementError: String
): List<ReportLine>? = when (selectedReport) {
        "الرصيد المحاسبي الكامل للعملاء" -> if (customerStatementError.isNotBlank()) emptyList() else {
            val names = cloudSalesDocuments.filter { it.customerId.isNotBlank() }
                .associate { it.customerId to it.customerName.ifBlank { it.customerId } }
            cloudCustomerPartyAccounts.filter { customerFilter.isBlank() || (names[it.partyId] ?: it.partyId).contains(customerFilter.trim(), true) || it.partyId.contains(customerFilter.trim(), true) }
                .groupBy { it.partyId to it.currencyCode }.flatMap { (party, links) ->
                    val accountIds = links.map { it.partyAccountId }.toSet()
                    val controlAccountIds = cloudGlMappings.filter {
                        it.mappingKey == "accounts_receivable" && it.currencyCode == party.second
                    }.map { it.ledgerAccountId }.toSet()
                    val movements = cloudLedgerLines.filter {
                        it.partyAccountId in accountIds && it.accountId in controlAccountIds && it.currencyCode == party.second
                    }
                        .mapNotNull { line ->
                            val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull() ?: return@mapNotNull null
                            Triple(line, date, line.debitMinor - line.creditMinor)
                        }.sortedWith(compareBy<Triple<CommercialCloudJournalLine, LocalDate, Long>> { it.second }.thenBy { it.first.entryNumber }.thenBy { it.first.lineNumber })
                    val opening = movements.filter { it.second < (startDate ?: LocalDate.MIN) }.sumOf { it.third }
                    val period = movements.filter { startDate != null && endDate != null && !it.second.isBefore(startDate) && !it.second.isAfter(endDate) }
                    if (period.isEmpty() && opening == 0L) emptyList() else buildList {
                        var balance = opening
                        val name = names[party.first] ?: party.first
                        if (opening != 0L) add(ReportLine(startText, "رصيد سابق", name, opening, party.second, "رصيد افتتاحي من دفتر الأستاذ · العميل ${party.first}"))
                        period.forEach { (line, date, amount) ->
                            balance = Math.addExact(balance, amount)
                            add(ReportLine(date.toString(), "قيد ${line.entryNumber} · سطر ${line.lineNumber}", name, amount, party.second,
                                "${line.entryDescription} · المصدر ${line.sourceType}:${line.sourceId} · الرصيد ${reportMoney(balance, party.second, true)}"))
                        }
                        add(ReportLine(endText, "رصيد ختامي", name, balance, party.second, "رصيد حساب مراقبة العميل من القيود المرحلة · ${party.first}"))
                    }
                }
        }
        "أرصدة الموردين والمستحقات" -> if (supplierStatementError.isNotBlank()) emptyList() else {
            val names = cloudPurchases.filter { it.supplierId.isNotBlank() }
                .associate { it.supplierId to it.supplierName.ifBlank { it.supplierId } }
            cloudSupplierPartyAccounts.filter { supplierFilter.isBlank() || (names[it.partyId] ?: it.partyId).contains(supplierFilter.trim(), true) || it.partyId.contains(supplierFilter.trim(), true) }
                .groupBy { it.partyId to it.currencyCode }.flatMap { (party, links) ->
                    val accountIds = links.map { it.partyAccountId }.toSet()
                    val controlAccountIds = cloudSupplierControlAccounts.filter {
                        it.accountRole == "accounts_payable" && it.currencyCode == party.second
                    }.map { it.ledgerAccountId }.toSet()
                    val movements = cloudLedgerLines.filter {
                        it.partyAccountId in accountIds && it.accountId in controlAccountIds && it.currencyCode == party.second
                    }
                        .mapNotNull { line ->
                            val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull() ?: return@mapNotNull null
                            Triple(line, date, line.creditMinor - line.debitMinor)
                        }.sortedWith(compareBy<Triple<CommercialCloudJournalLine, LocalDate, Long>> { it.second }.thenBy { it.first.entryNumber }.thenBy { it.first.lineNumber })
                    val opening = movements.filter { it.second < (startDate ?: LocalDate.MIN) }.sumOf { it.third }
                    val period = movements.filter { startDate != null && endDate != null && !it.second.isBefore(startDate) && !it.second.isAfter(endDate) }
                    if (period.isEmpty() && opening == 0L) emptyList() else buildList {
                        var balance = opening
                        val name = names[party.first] ?: party.first
                        if (opening != 0L) add(ReportLine(startText, "رصيد سابق", name, opening, party.second, "رصيد افتتاحي من دفتر الأستاذ · المورد ${party.first}"))
                        period.forEach { (line, date, amount) ->
                            balance = Math.addExact(balance, amount)
                            add(ReportLine(date.toString(), "قيد ${line.entryNumber} · سطر ${line.lineNumber}", name, amount, party.second,
                                "${line.entryDescription} · المصدر ${line.sourceType}:${line.sourceId} · الرصيد ${reportMoney(balance, party.second, true)}"))
                        }
                        add(ReportLine(endText, "رصيد ختامي", name, balance, party.second, "رصيد حساب مراقبة المورد من القيود المرحلة · ${party.first}"))
                    }
                }
        }
        "أرصدة العملاء وكشف الحساب" -> {
            val movements = buildCustomerOperationalStatementMovements(cloudSalesDocuments.toCustomerStatementInputs())
                .filter { movement ->
                    customerFilter.isBlank() ||
                        movement.customer.contains(customerFilter.trim(), true) ||
                        movement.customerId.contains(customerFilter.trim(), true)
                }
            movements.groupBy { Triple(it.customerId, it.customer, it.currency) }
                .toSortedMap(compareBy<Triple<String, String, String>> { it.second }.thenBy { it.third })
                .flatMap { (key, customerMoves) ->
                    val ordered = customerMoves.sortedWith(compareBy<ReportCustomerMovement> { it.date }.thenBy { it.reference })
                    val opening = sumReportAmountsExactly(
                        ordered.filter { reportDateMillis(it.date)?.let { ts -> ts < startMillis } == true }.map { it.amount }
                    )
                    val periodMoves = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts >= startMillis && ts < endExclusive } == true }
                    if (periodMoves.isEmpty() && opening == 0L) emptyList() else buildList {
                        var balance = opening
                        if (opening != 0L) add(ReportLine(startText, "سابق", key.second, opening, key.third, "رصيد سابق محسوب من الحركات المسجلة"))
                        periodMoves.forEach { movement ->
                            balance = Math.addExact(balance, movement.amount)
                            add(ReportLine(movement.date, movement.reference, movement.customer, movement.amount, movement.currency,
                                "${movement.description} · الرصيد: ${reportMoney(balance, movement.currency, true)}"))
                        }
                        add(ReportLine(endText, "ختامي", key.second, balance, key.third, "الرصيد الختامي من الفواتير والتحصيلات والمرتجعات المكتملة فقط"))
                    }
                }
        }
        else -> null
}
