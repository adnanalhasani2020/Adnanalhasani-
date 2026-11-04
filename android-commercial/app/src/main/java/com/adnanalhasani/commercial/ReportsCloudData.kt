package com.adnanalhasani.commercial

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot of every cloud projection consumed by the reports center.
 * Keeping the payload and its source errors together prevents the screen from
 * accidentally presenting stale rows as a successful refresh.
 */
internal data class ReportsCloudData(
    val salesDocuments: List<CommercialCloudSaleDocument> = emptyList(),
    val receivables: List<CommercialCloudReceivable> = emptyList(),
    val receivablesLoadError: String = "",
    val purchases: List<CommercialCloudPurchaseDocument> = emptyList(),
    val supplierPayments: List<CommercialCloudSupplierPaymentMovement> = emptyList(),
    val stockMovements: List<CommercialCloudStockMovement> = emptyList(),
    val stockAlerts: List<CommercialCloudStockAlert> = emptyList(),
    val locationInventoryBalances: List<CommercialCloudLocationInventoryBalance> = emptyList(),
    val locationInventoryLoadError: String = "",
    val warehouseInventoryContext: List<CommercialCloudWarehouseInventoryContext> = emptyList(),
    val warehouseInventoryContextLoadError: String = "",
    val returns: List<CommercialCloudSaleReturn> = emptyList(),
    val payments: List<CommercialCloudRecord> = emptyList(),
    val journals: List<CommercialCloudRecord> = emptyList(),
    val ledgerLines: List<CommercialCloudJournalLine> = emptyList(),
    val supplierControlAccounts: List<CommercialCloudSupplierControlAccount> = emptyList(),
    val glMappings: List<CommercialCloudGlAccountMapping> = emptyList(),
    val glMappingsLoadError: String = "",
    val customerPartyAccounts: List<CommercialCloudPartyAccountLink> = emptyList(),
    val supplierPartyAccounts: List<CommercialCloudPartyAccountLink> = emptyList(),
    val customerPartyAccountsLoadError: String = "",
    val supplierPartyAccountsLoadError: String = "",
    val supplierControlAccountsLoadError: String = "",
    val expenses: List<CommercialCloudExpenseVoucher> = emptyList(),
    val loading: Boolean = false,
    val loadError: String = "",
    val salesDocumentsLoadError: String = "",
    val purchasesLoadError: String = "",
    val returnsLoadError: String = "",
    val paymentsLoadError: String = "",
    val journalsLoadError: String = "",
    val ledgerLinesLoadError: String = "",
    val expensesLoadError: String = "",
    val supplierPaymentsLoadError: String = "",
    val stockMovementsLoadError: String = "",
    val stockAlertsLoadError: String = ""
)

/**
 * Owns cloud report retrieval and refresh lifecycle. Each source is isolated:
 * a failed projection is cleared and carries its own error, matching the
 * previous screen behavior and preserving fail-closed report availability.
 */
@Composable
internal fun rememberReportsCloudData(
    context: Context,
    enabled: Boolean,
    reloadKey: Int
): ReportsCloudData {
    var data by remember { mutableStateOf(ReportsCloudData()) }

    LaunchedEffect(enabled, reloadKey) {
        if (!enabled) return@LaunchedEffect
        data = data.copy(loading = true, loadError = "")

        val loaded = withContext(Dispatchers.IO) {
            val salesDocuments = runCatching { loadCommercialCloudSaleDocuments(context) }
            val receivables = runCatching { loadCommercialCloudReceivables(context) }
            val purchases = runCatching { loadCommercialCloudPurchaseDocuments(context) }
            val supplierPayments = runCatching { loadCommercialCloudSupplierPaymentMovements(context) }
            val stockMovements = runCatching { loadCommercialCloudStockMovements(context) }
            val stockAlerts = runCatching { loadCommercialCloudStockAlerts(context) }
            val locationInventory = runCatching { loadCommercialCloudLocationInventoryBalances(context) }
            val warehouseInventoryContext = runCatching { loadCommercialCloudWarehouseInventoryContext(context) }
            val returns = runCatching { loadCommercialCloudSaleReturns(context) }
            val payments = runCatching { loadCommercialCloudRecords(context, "payments") }
            val journals = runCatching { loadCommercialCloudRecords(context, "journals") }
            val ledgerLines = runCatching { loadCommercialCloudPostedJournalLines(context) }
            val supplierControlAccounts = runCatching { loadCommercialCloudSupplierControlAccounts(context) }
            val glMappings = runCatching { loadCommercialCloudGlAccountMappings(context) }
            val customerPartyAccounts = runCatching { loadCommercialCloudCustomerPartyAccounts(context) }
            val supplierPartyAccounts = runCatching { loadCommercialCloudSupplierPartyAccounts(context) }
            val expenses = runCatching { loadCommercialCloudExpenses(context) }

            val errors = listOf(
                salesDocuments.exceptionOrNull(),
                purchases.exceptionOrNull(),
                returns.exceptionOrNull(),
                payments.exceptionOrNull(),
                journals.exceptionOrNull(),
                ledgerLines.exceptionOrNull(),
                expenses.exceptionOrNull()
            ).filterNotNull()

            ReportsCloudData(
                salesDocuments = salesDocuments.getOrDefault(emptyList()),
                receivables = receivables.getOrDefault(emptyList()),
                receivablesLoadError = receivables.exceptionOrNull()?.message.orEmpty(),
                purchases = purchases.getOrDefault(emptyList()),
                supplierPayments = supplierPayments.getOrDefault(emptyList()),
                supplierPaymentsLoadError = supplierPayments.exceptionOrNull()?.message.orEmpty(),
                stockMovements = stockMovements.getOrDefault(emptyList()),
                stockMovementsLoadError = stockMovements.exceptionOrNull()?.message.orEmpty(),
                stockAlerts = stockAlerts.getOrDefault(emptyList()),
                stockAlertsLoadError = stockAlerts.exceptionOrNull()?.message.orEmpty(),
                locationInventoryBalances = locationInventory.getOrDefault(emptyList()),
                locationInventoryLoadError = locationInventory.exceptionOrNull()?.message.orEmpty(),
                warehouseInventoryContext = warehouseInventoryContext.getOrDefault(emptyList()),
                warehouseInventoryContextLoadError = warehouseInventoryContext.exceptionOrNull()?.message.orEmpty(),
                returns = returns.getOrDefault(emptyList()),
                payments = payments.getOrDefault(emptyList()),
                journals = journals.getOrDefault(emptyList()),
                ledgerLines = ledgerLines.getOrDefault(emptyList()),
                supplierControlAccounts = supplierControlAccounts.getOrDefault(emptyList()),
                supplierControlAccountsLoadError = supplierControlAccounts.exceptionOrNull()?.message.orEmpty(),
                glMappings = glMappings.getOrDefault(emptyList()),
                glMappingsLoadError = glMappings.exceptionOrNull()?.message.orEmpty(),
                customerPartyAccounts = customerPartyAccounts.getOrDefault(emptyList()),
                customerPartyAccountsLoadError = customerPartyAccounts.exceptionOrNull()?.message.orEmpty(),
                supplierPartyAccounts = supplierPartyAccounts.getOrDefault(emptyList()),
                supplierPartyAccountsLoadError = supplierPartyAccounts.exceptionOrNull()?.message.orEmpty(),
                expenses = expenses.getOrDefault(emptyList()),
                loading = false,
                loadError = errors.firstOrNull()?.message
                    ?: if (errors.isNotEmpty()) "تعذر تحميل بعض مصادر التقارير." else "",
                salesDocumentsLoadError = salesDocuments.exceptionOrNull()?.message.orEmpty(),
                purchasesLoadError = purchases.exceptionOrNull()?.message.orEmpty(),
                returnsLoadError = returns.exceptionOrNull()?.message.orEmpty(),
                paymentsLoadError = payments.exceptionOrNull()?.message.orEmpty(),
                journalsLoadError = journals.exceptionOrNull()?.message.orEmpty(),
                ledgerLinesLoadError = ledgerLines.exceptionOrNull()?.message.orEmpty(),
                expensesLoadError = expenses.exceptionOrNull()?.message.orEmpty()
            )
        }
        data = loaded
    }

    return data
}
