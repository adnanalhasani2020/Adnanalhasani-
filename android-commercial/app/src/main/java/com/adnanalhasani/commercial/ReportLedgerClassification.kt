package com.adnanalhasani.commercial

import java.util.Locale

internal fun normalizedReportAccountType(line: CommercialCloudJournalLine): String =
    line.accountType.lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')

internal fun reportStatementClass(line: CommercialCloudJournalLine): String =
    when (normalizedReportAccountType(line)) {
        "asset", "assets" -> "asset"
        "liability", "liabilities" -> "liability"
        "equity" -> "equity"
        "revenue", "income" -> if (
            line.normalBalance.equals("debit", true) ||
            line.accountName.contains("مردودات", true) ||
            line.accountName.contains("خصومات", true) ||
            line.accountName.contains("sales return", true) ||
            line.accountName.contains("sales discount", true)
        ) "contra_revenue" else "revenue"
        "contra_revenue" -> "contra_revenue"
        "cost_of_goods_sold", "cost_of_sales" -> "cogs"
        "expense", "expenses" -> if (
            line.accountName.contains("تكلفة البضاعة المباعة", true) ||
            line.accountName.contains("cost of goods sold", true) ||
            line.accountName.contains("تكلفة المبيعات", true) ||
            line.accountCode == "5000"
        ) "cogs" else "expense"
        else -> "unknown"
    }

internal fun reportAccountNet(lines: List<CommercialCloudJournalLine>, accountClass: String): Long =
    sumReportAmountsExactly(
        lines.filter { reportStatementClass(it) == accountClass }.map { line ->
            if (accountClass in setOf("asset", "expense", "cogs", "contra_revenue")) {
                Math.subtractExact(line.debitMinor, line.creditMinor)
            } else {
                Math.subtractExact(line.creditMinor, line.debitMinor)
            }
        }
    )
