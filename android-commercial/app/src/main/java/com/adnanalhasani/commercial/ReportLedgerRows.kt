package com.adnanalhasani.commercial

import java.time.LocalDate

/** Builds rows for posted-ledger financial statements and ledger activity reports. */
internal fun buildLedgerReportRows(
    selectedReport: String,
    ledgerStatementError: String,
    cloudLedgerLines: List<CommercialCloudJournalLine>,
    cloudJournals: List<CommercialCloudRecord>,
    startDate: LocalDate?,
    endDate: LocalDate?,
    startMillis: Long,
    endExclusive: Long,
    startText: String,
    endText: String,
    accountFilter: String
): List<ReportLine>? = when (selectedReport) {
        "قائمة الدخل والميزانية العمومية" -> if (ledgerStatementError.isNotBlank()) emptyList() else {
            val throughEnd = cloudLedgerLines.filter { line ->
                val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                date != null && endDate != null && !date.isAfter(endDate)
            }
            val inPeriod = throughEnd.filter { line ->
                val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                date != null && startDate != null && endDate != null &&
                    !date.isBefore(startDate) && !date.isAfter(endDate)
            }
            val periodDays = if (startDate != null && endDate != null)
                java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0L) + 1L else 0L
            val previousStart = if (startDate != null && periodDays > 0L) runCatching { startDate.minusDays(periodDays) }.getOrNull() else null
            val previousEnd = if (startDate != null) runCatching { startDate.minusDays(1) }.getOrNull() else null
            val previousPeriod = if (previousStart != null && previousEnd != null) throughEnd.filter { line ->
                val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                date != null && !date.isBefore(previousStart) && !date.isAfter(previousEnd)
            } else emptyList()
            val currencies = (throughEnd.map { it.currencyCode } + inPeriod.map { it.currencyCode })
                .filter { it.matches(Regex("^[A-Z]{3}$")) }.distinct().sorted()
            buildList {
                currencies.forEach { currency ->
                    val periodRows = inPeriod.filter { it.currencyCode == currency }
                    val cumulativeRows = throughEnd.filter { it.currencyCode == currency }
                    periodRows.groupBy { Triple(it.accountId, it.accountName.ifBlank { it.accountCode }, reportStatementClass(it)) }
                        .filterKeys { it.third in setOf("revenue", "contra_revenue", "cogs", "expense") }
                        .toSortedMap(compareBy<Triple<String, String, String>> { it.third }.thenBy { it.second }.thenBy { it.first })
                        .forEach { (key, rows) ->
                            val amount = sumReportAmountsExactly(rows.map {
                                Math.subtractExact(it.creditMinor, it.debitMinor)
                            })
                            add(ReportLine("${startText} — ${endText}", "حساب ${key.first}", key.second, amount, currency,
                                "تفصيل حساب قائمة الدخل · التصنيف ${key.third} · دفتر الأستاذ المرحل"))
                        }
                    val revenue = reportAccountNet(periodRows, "revenue")
                    val contraRevenue = reportAccountNet(periodRows, "contra_revenue")
                    val cogs = reportAccountNet(periodRows, "cogs")
                    val operatingExpenses = reportAccountNet(periodRows, "expense")
                    val netRevenue = Math.subtractExact(revenue, contraRevenue)
                    val grossProfit = Math.subtractExact(netRevenue, cogs)
                    val netProfit = Math.subtractExact(grossProfit, operatingExpenses)
                    val previousRows = previousPeriod.filter { it.currencyCode == currency }
                    if (previousRows.isNotEmpty()) {
                        val previousRevenue = Math.subtractExact(
                            reportAccountNet(previousRows, "revenue"),
                            reportAccountNet(previousRows, "contra_revenue")
                        )
                        val previousGrossProfit = Math.subtractExact(previousRevenue, reportAccountNet(previousRows, "cogs"))
                        val previousNetProfit = Math.subtractExact(previousGrossProfit, reportAccountNet(previousRows, "expense"))
                        add(ReportLine("${previousStart} — ${previousEnd}", "مقارنة الفترة السابقة", "صافي الإيرادات للفترة السابقة", previousRevenue, currency,
                            "الفترة السابقة مساوية للمدة المحددة؛ من القيود المرحلة فقط"))
                        add(ReportLine("${previousStart} — ${previousEnd}", "مقارنة الفترة السابقة", "مجمل الربح للفترة السابقة", previousGrossProfit, currency,
                            "صافي الإيرادات السابقة ناقص تكلفة البضاعة المرحلة"))
                        add(ReportLine("${previousStart} — ${previousEnd}", "مقارنة الفترة السابقة", "صافي الربح/الخسارة للفترة السابقة", previousNetProfit, currency,
                            "النتيجة السابقة من حسابات الإيرادات والمصروفات المرحلة"))
                    }
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "الإيرادات المسجلة", revenue, currency, "مصدرها حسابات الإيرادات المرحلة"))
                    if (contraRevenue != 0L) add(ReportLine(startText + " — " + endText, "قائمة الدخل", "المرتجعات/الخصومات المسجلة كحساب مقابل للإيراد", Math.negateExact(contraRevenue), currency, "يعتمد على تصنيف الحساب في دفتر الأستاذ"))
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "صافي الإيرادات", netRevenue, currency, "الإيرادات ناقص الحسابات المقابلة للإيراد"))
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "تكلفة البضاعة المباعة", Math.negateExact(cogs), currency, "حسابات التكلفة المرحلة فقط"))
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "مجمل الربح", grossProfit, currency, "صافي الإيرادات ناقص تكلفة البضاعة"))
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "المصروفات التشغيلية والأخرى", Math.negateExact(operatingExpenses), currency, "حسابات المصروفات المرحلة"))
                    add(ReportLine(startText + " — " + endText, "قائمة الدخل", "صافي الربح/الخسارة للفترة", netProfit, currency, "لا يتضمن حسابات مجهولة النوع؛ تم التحقق من اتزان القيود"))
                    cumulativeRows.groupBy { Triple(it.accountId, it.accountName.ifBlank { it.accountCode }, reportStatementClass(it)) }
                        .filterKeys { it.third in setOf("asset", "liability", "equity") }
                        .toSortedMap(compareBy<Triple<String, String, String>> { it.third }.thenBy { it.second }.thenBy { it.first })
                        .forEach { (key, rows) ->
                            val amount = sumReportAmountsExactly(rows.map { line ->
                                if (key.third == "asset") Math.subtractExact(line.debitMinor, line.creditMinor)
                                else Math.subtractExact(line.creditMinor, line.debitMinor)
                            })
                            add(ReportLine(endText, "حساب ${key.first}", key.second, amount, currency,
                                "رصيد حساب ${key.third} حتى تاريخ التقرير · دفتر الأستاذ المرحل"))
                        }
                    val assets = reportAccountNet(cumulativeRows, "asset")
                    val liabilities = reportAccountNet(cumulativeRows, "liability")
                    val equity = reportAccountNet(cumulativeRows, "equity")
                    val cumulativeIncome = Math.subtractExact(
                        Math.subtractExact(
                            Math.subtractExact(
                                reportAccountNet(cumulativeRows, "revenue"),
                                reportAccountNet(cumulativeRows, "contra_revenue")
                            ),
                            reportAccountNet(cumulativeRows, "cogs")
                        ),
                        reportAccountNet(cumulativeRows, "expense")
                    )
                    val totalEquity = Math.addExact(equity, cumulativeIncome)
                    val equationDifference = Math.subtractExact(Math.subtractExact(assets, liabilities), totalEquity)
                    add(ReportLine(endText, "الميزانية العمومية", "إجمالي الأصول", assets, currency, "رصيد حتى تاريخ التقرير"))
                    add(ReportLine(endText, "الميزانية العمومية", "إجمالي الالتزامات", liabilities, currency, "رصيد حتى تاريخ التقرير"))
                    add(ReportLine(endText, "الميزانية العمومية", "حقوق الملكية المسجلة", equity, currency, "لا يشمل نتيجة الفترات غير المقفلة"))
                    add(ReportLine(endText, "الميزانية العمومية", "نتائج متراكمة غير مقفلة", cumulativeIncome, currency, "إيرادات ومصروفات مرحّلة منذ بداية دفتر الأستاذ"))
                    add(ReportLine(endText, "الميزانية العمومية", "إجمالي حقوق الملكية شاملاً النتائج غير المقفلة", totalEquity, currency, "حقوق الملكية المسجلة + صافي النتائج المتراكمة"))
                    add(ReportLine(endText, "اختبار المعادلة", "الأصول − الالتزامات − حقوق الملكية", equationDifference, currency,
                        if (equationDifference == 0L) "متزن حسابيًا حسب دفتر الأستاذ المحمل" else "فرق يحتاج إلى مطابقة؛ لا تعتمد الميزانية حتى تفسيره"))
                }
            }
        }
        "ميزان المراجعة حسب الحساب" -> {
            val throughEnd = cloudLedgerLines.filter { line ->
                val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                date != null && endDate != null && !date.isAfter(endDate)
            }
            val accountRows = throughEnd.groupBy { Triple(it.accountId, it.accountName.ifBlank { "حساب غير معروف" }, it.currencyCode.ifBlank { "غير محددة" }) }
                .map { (key, rows) ->
                    val debit = sumReportAmountsExactly(rows.map { it.debitMinor })
                    val credit = sumReportAmountsExactly(rows.map { it.creditMinor })
                    ReportLine(endText, key.first, key.second, Math.subtractExact(debit, credit), key.third,
                        "مدين: ${reportMoney(debit, key.third, true)} · دائن: ${reportMoney(credit, key.third, true)}")
                }
            val totals = throughEnd.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
                val debit = sumReportAmountsExactly(rows.map { it.debitMinor })
                val credit = sumReportAmountsExactly(rows.map { it.creditMinor })
                val difference = Math.subtractExact(debit, credit)
                ReportLine(endText, "إجمالي", "إجمالي الميزان", difference, currency,
                    "إجمالي المدين: ${reportMoney(debit, currency, true)} · إجمالي الدائن: ${reportMoney(credit, currency, true)} · الفرق: ${reportMoney(difference, currency, true)}")
            }
            accountRows.sortedBy { it.reference } + totals
        }
        "حركة دفتر الأستاذ العام", "كشف حساب محاسبي" -> {
            val accountQuery = accountFilter.trim()
            cloudLedgerLines.filter { line ->
                val timestamp = reportDateMillis(line.entryDate)
                timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                    (selectedReport != "كشف حساب محاسبي" || (accountQuery.isNotBlank() &&
                        listOf(line.accountId, line.accountCode, line.accountName).any { it.contains(accountQuery, true) }))
            }.map { line ->
                ReportLine(line.entryDate.take(10), line.entryNumber.ifBlank { line.entryId }, "${line.accountCode} · ${line.accountName}",
                    Math.subtractExact(line.debitMinor, line.creditMinor), line.currencyCode,
                    "قيد ${line.entryNumber} · مدين: ${line.debitMinor} · دائن: ${line.creditMinor} · مصدر: ${line.sourceType} ${line.sourceId}")
            }.sortedByDescending { it.date }
        }
        "تفاصيل القيود المرحلة" -> cloudLedgerLines.filter { line ->
            val timestamp = reportDateMillis(line.entryDate)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { line ->
            ReportLine(line.entryDate.take(10), "${line.entryNumber.ifBlank { line.entryId }} · سطر ${line.lineNumber}",
                "${line.accountCode} · ${line.accountName}", Math.subtractExact(line.debitMinor, line.creditMinor), line.currencyCode,
                "مدين: ${reportMoney(line.debitMinor, line.currencyCode, true)} · دائن: ${reportMoney(line.creditMinor, line.currencyCode, true)} · ${line.entryDescription} · المصدر: ${line.sourceType} ${line.sourceId}")
        }.sortedWith(compareBy<ReportLine> { it.date }.thenBy { it.reference })
        "سجل القيود المحاسبية" -> cloudJournals.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, 0L, "", row.status + " · " + row.subtitle)
        }.sortedByDescending { it.date }
        else -> null
}
