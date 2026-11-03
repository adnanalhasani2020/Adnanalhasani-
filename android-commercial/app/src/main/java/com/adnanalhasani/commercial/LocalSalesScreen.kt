package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SalesScreen(
    sales: List<SaleRecord>,
    cloudMode: Boolean,
    onNewSale: () -> Unit,
    onCloudInvoices: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Heading("المبيعات والفواتير", if (cloudMode) "ملخص الفواتير المحمّلة من قاعدة البيانات" else "تابع عمليات البيع والتحصيل") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("إجمالي المبيعات", money(sales.sumOf { it.total }), "الفواتير المحفوظة", Icons.Default.AttachMoney, Modifier.weight(1f))
                Metric("عدد الفواتير", sales.size.toString(), "فاتورة", Icons.Default.PointOfSale, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("فاتورة بيع جديدة") } }
        if (cloudMode) item {
            OutlinedButton(onClick = onCloudInvoices, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ReceiptLong, null)
                Spacer(Modifier.width(8.dp))
                Text("عرض تفاصيل الفواتير السحابية")
            }
        }
        item { Heading("أحدث الفواتير", if (cloudMode) "ملخص من قاعدة البيانات؛ التفاصيل من شاشة الفواتير السحابية" else "الفواتير المحفوظة على هذا الجهاز") }
        items(sales) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} ${sale.unitCode}", money(sale.total), sale.credit) }
        
    }
}
