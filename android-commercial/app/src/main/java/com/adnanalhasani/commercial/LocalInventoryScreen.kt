package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun InventoryScreen(products: List<Product>, query: String, onQuery: (String) -> Unit, onAddProduct: () -> Unit, onEditProduct: (Int) -> Unit) {
    val filtered = products.filter { it.name.contains(query, true) || it.category.contains(query, true) || it.description.contains(query, true) || it.barcode.contains(query, true) || it.catalogCode.contains(query, true) || productMatchesUnitQuery(it, query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Heading("المخزون والمنتجات", "إدارة الأصناف والكميات والأسعار") }
        item {
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("ابحث عن منتج أو تصنيف") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("إجمالي الأصناف", products.size.toString(), "صنف", Icons.Default.Inventory2, Modifier.weight(1f))
                Metric("مخزون منخفض", products.count { isCommercialStockLow(it.stockPrecise, it.reorderPoint.takeIf { threshold -> threshold >= 0 } ?: 9) }.toString(), "حسب حد التنبيه", Icons.Default.WarningAmber, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onAddProduct, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("إضافة منتج") } }
        item { Heading("قائمة المنتجات", "عدد النتائج: " + filtered.size) }
        items(filtered) { product -> ProductRow(product, onClick = { onEditProduct(products.indexOf(product)) }) }
        if (filtered.isEmpty()) item { Text("لا توجد منتجات تطابق البحث.", color = Muted) }
    }
}
