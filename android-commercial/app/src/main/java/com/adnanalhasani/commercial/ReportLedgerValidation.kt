package com.adnanalhasani.commercial

import java.time.LocalDate
import java.util.Locale

private val requiredReportGlMappingKeys = setOf(
    "accounts_receivable",
    "sales_revenue",
    "sales_returns",
    "sales_discounts",
    "sales_tax_payable",
    "inventory",
    "cost_of_goods_sold"
)

private val requiredReportSupplierAccountRoles = setOf(
    "inventory_asset",
    "accounts_payable",
    "goods_received_not_invoiced",
    "purchase_price_variance",
    "purchase_tax_freight_expense"
)

private val supportedReportLedgerAccountTypes = setOf(
    "asset", "assets", "liability", "liabilities", "equity", "revenue", "income",
    "expense", "expenses", "cost_of_goods_sold", "cost_of_sales", "contra_revenue"
)

/**
 * Validates the posted-ledger sources before financial statements are allowed to render.
 * Keep the original fail-closed precedence and currency-specific balancing behavior here.
 */
internal fun reportLedgerStatementValidationError(
    cloudMode: Boolean,
    ledgerLines: List<CommercialCloudJournalLine>,
    endDate: LocalDate?,
    ledgerLinesLoadError: String,
    glMappingsLoadError: String,
    glMappingKeys: Set<String>,
    supplierControlAccountsLoadError: String,
    supplierAccountRoles: Set<String>
): String {
    if (!cloudMode) return "القوائم المالية تتطلب دفتر الأستاذ السحابي."

    val invalidPostedDate = ledgerLines.any { line ->
        runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull() == null
    }
    val throughEnd = ledgerLines.filter { line ->
        val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
        date != null && endDate != null && !date.isAfter(endDate)
    }
    val missingGlMappings = requiredReportGlMappingKeys - glMappingKeys
    val missingSupplierRoles = requiredReportSupplierAccountRoles - supplierAccountRoles

    return when {
        ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
        glMappingsLoadError.isNotBlank() -> glMappingsLoadError
        missingGlMappings.isNotEmpty() ->
            "خرائط حسابات المبيعات والذمم والمخزون غير مكتملة: ${missingGlMappings.sorted().joinToString()}."
        invalidPostedDate ->
            "توجد قيود مرحلة بتاريخ غير صالح؛ لا يمكن استبعادها بأمان من القوائم المالية."
        supplierControlAccountsLoadError.isNotBlank() -> supplierControlAccountsLoadError
        missingSupplierRoles.isNotEmpty() ->
            "خرائط حسابات المشتريات والموردين غير مكتملة: ${missingSupplierRoles.sorted().joinToString()}."
        throughEnd.isEmpty() -> "لا توجد قيود مرحلة ضمن الفترة؛ لا يمكن اعتماد القوائم المالية."
        else -> {
            val balanceChecks = throughEnd.groupBy { it.entryId to it.currencyCode }.values.map { lines ->
                runCatching {
                    val debitTotal = lines.fold(0L) { total, line -> Math.addExact(total, line.debitMinor) }
                    val creditTotal = lines.fold(0L) { total, line -> Math.addExact(total, line.creditMinor) }
                    debitTotal == creditTotal
                }
            }
            val balanceOverflow = balanceChecks.any { it.isFailure }
            val unbalanced = balanceChecks.any { it.getOrNull() == false }
            val unknown = throughEnd.any { line ->
                line.accountType.lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_') !in
                    supportedReportLedgerAccountTypes
            }
            val invalidCurrency = throughEnd.any { !it.currencyCode.matches(Regex("^[A-Z]{3}$")) }
            when {
                balanceOverflow -> "تجاوزت مجاميع المدين أو الدائن نطاق الأرقام الدقيق؛ تم حجب القوائم."
                unbalanced -> "توجد قيود مرحلة غير متزنة حسب العملة؛ تم حجب القوائم."
                unknown -> "توجد حسابات بأنواع غير معروفة؛ يلزم تصنيف دليل الحسابات قبل اعتماد القوائم."
                invalidCurrency -> "توجد سطور أستاذ بلا رمز عملة ISO صالح؛ تم حجب القوائم لمنع خلط العملات."
                else -> ""
            }
        }
    }
}
