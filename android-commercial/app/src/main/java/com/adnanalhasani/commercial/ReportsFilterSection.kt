package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Adds the party, row, currency, status, and ledger-account filters to the parent list. */
internal fun LazyListScope.reportsFilterSection(
    selectedReport: String,
    supplierFilter: String,
    customerFilter: String,
    rowFilter: String,
    currencyFilter: String,
    statusFilter: String,
    accountFilter: String,
    onSupplierFilterChanged: (String) -> Unit,
    onCustomerFilterChanged: (String) -> Unit,
    onRowFilterChanged: (String) -> Unit,
    onCurrencyFilterChanged: (String) -> Unit,
    onStatusFilterChanged: (String) -> Unit,
    onAccountFilterChanged: (String) -> Unit
) {
    if (selectedReport in setOf("كشف حركات الموردين", "أرصدة الموردين والمستحقات")) item {
        OutlinedTextField(
            value = supplierFilter,
            onValueChange = onSupplierFilterChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("اسم المورد") },
            supportingText = { Text(if (selectedReport == "أرصدة الموردين والمستحقات") "يعرض حركات حساب مراقبة المورد من القيود المرحلة." else "الكشف التشغيلي الجزئي يعتمد على الفواتير والدفعات المحملة.") }
        )
    }
    if (selectedReport in setOf("أرصدة العملاء وكشف الحساب", "الرصيد المحاسبي الكامل للعملاء")) item {
        OutlinedTextField(
            value = customerFilter,
            onValueChange = onCustomerFilterChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("اسم العميل أو معرفه") },
            supportingText = { Text(if (selectedReport == "الرصيد المحاسبي الكامل للعملاء") "يصفّي حركات حساب مراقبة العميل في دفتر الأستاذ المرحل." else "يصفّي الكشف التشغيلي حسب العميل والحركات المحملة.") }
        )
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تصفية نتائج التقرير", fontWeight = FontWeight.SemiBold, color = ReportInk)
            if (selectedReport !in setOf("أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات")) {
                OutlinedTextField(rowFilter, onRowFilterChanged, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم أو مرجع المستند") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(currencyFilter, onCurrencyFilterChanged, modifier = Modifier.weight(1f), singleLine = true, label = { Text("العملة") })
                if (selectedReport !in setOf("أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات")) {
                    OutlinedTextField(statusFilter, onStatusFilterChanged, modifier = Modifier.weight(1f), singleLine = true, label = { Text("الحالة") })
                }
            }
        }
    }
    if (selectedReport == "كشف حساب محاسبي") item {
        OutlinedTextField(
            value = accountFilter,
            onValueChange = onAccountFilterChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("رمز الحساب أو اسمه") },
            supportingText = { Text("أدخل رمزًا أو اسمًا مطابقًا للحساب؛ لا يعرض الكشف كل الحسابات تلقائيًا.") }
        )
    }
}
