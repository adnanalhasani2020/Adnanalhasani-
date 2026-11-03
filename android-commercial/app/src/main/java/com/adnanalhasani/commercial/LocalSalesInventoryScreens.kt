package com.adnanalhasani.commercial

import org.json.JSONArray
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Dashboard(products: List<Product>, sales: List<SaleRecord>, cloudMode: Boolean, onNewSale: () -> Unit, onInventory: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        if (products.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Mint),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("لنبدأ بتجهيز متجرك", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("1) أضف الأصناف من المخزون. 2) حدّد الوحدة والسعر والكمية الافتتاحية. 3) انتقل إلى المبيعات لتسجيل أول فاتورة. بياناتك السحابية ستظهر هنا بعد تحميلها.", color = Muted, fontSize = 13.sp)
                        Button(onClick = onInventory, modifier = Modifier.fillMaxWidth()) { Text("الانتقال إلى إضافة الأصناف") }
                    }
                }
            }
        } else if (sales.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Mint),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مخزونك جاهز — الخطوة التالية البيع", color = Ink, fontWeight = FontWeight.Bold)
                        Text("افتح شاشة المبيعات، اختر «فاتورة جديدة»، ثم راجع الكميات والأسعار قبل تأكيد العملية.", color = Muted, fontSize = 13.sp)
                        Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Text("تسجيل أول عملية بيع") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("أهلًا بك 👋", color = Color(0xFFBFD9D0), fontSize = 14.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("إليك ملخص المبيعات المسجلة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Box(Modifier.size(46.dp).background(Color(0xFF31584F), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF8DE0B8))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("إجمالي المبيعات المسجلة", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(sales.sumOf { it.total }), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(if (cloudMode) "${sales.size} فاتورة محمّلة من قاعدة البيانات" else "${sales.size} فاتورة محفوظة على هذا الجهاز", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                    Spacer(Modifier.height(17.dp))
                    Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("تسجيل عملية بيع")
                    }
                }
            }
        }
        item { Heading("ملخص المبيعات", if (cloudMode) "إحصاءات الفواتير المحمّلة من قاعدة البيانات" else "إحصاءات الفواتير المحفوظة على هذا الجهاز") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الفواتير", sales.size.toString(), "فاتورة محفوظة", Icons.Default.PointOfSale, Modifier.weight(1f))
                Metric("نقدي", money(sales.filter { !it.credit }.sumOf { it.total }), "إجمالي النقدي", Icons.Default.AttachMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الآجل", money(sales.filter { it.credit }.sumOf { it.total }), "إجمالي الآجل", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("المنتجات", products.size.toString(), "صنف مسجل", Icons.Default.Inventory2, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Heading("تنبيهات المخزون", "أصناف تحتاج إلى إعادة طلب") }
                TextButton(onClick = onInventory) { Text("عرض الكل") }
            }
        }
        items(products.filter { isCommercialStockLow(it.stockPrecise, 9) }.take(3)) { ProductRow(it) }
        item { Heading("آخر المبيعات", "أحدث الفواتير المسجلة") }
        items(sales.take(3)) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} ${sale.unitCode}", money(sale.total), sale.credit) }
        
    }
}

@Composable
private fun Metric(title: String, value: String, note: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(36.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Green) }
            Spacer(Modifier.height(10.dp))
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(note, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

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

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit = {}) {
    val warningThreshold = product.reorderPoint.takeIf { it >= 0 } ?: 9
    val warning = isCommercialStockLow(product.stockPrecise, warningThreshold)
    val displayImage = product.imageUrl.ifBlank { runCatching { val units = JSONArray(product.unitVariantsJson); (0 until units.length()).map { units.getJSONObject(it) }.firstOrNull { it.optBoolean("isDefault") && it.optString("imageUrl").startsWith("https://", true) }?.optString("imageUrl").orEmpty() }.getOrDefault("") }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (displayImage.startsWith("https://", true)) {
                AsyncImage(model=displayImage,contentDescription=product.name,modifier=Modifier.size(45.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop)
            } else {
                Box(Modifier.size(45.dp).background(if (warning) Color(0xFFFFF1DF) else Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (warning) Icons.Default.WarningAmber else Icons.Default.Inventory2, null, tint = if (warning) Color(0xFFB96A14) else Green)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(product.description.ifBlank { product.category }, color = Muted, fontSize = 12.sp)
                Text("المتاح للبيع: ${formatCommercialStockQuantity(product.stockPrecise)} ${product.unitCode.ifBlank { "EA" }}", color = if (warning) Color(0xFFB96A14) else Muted, fontSize = 11.sp)
                Text("حد التنبيه: ${if (product.reorderPoint >= 0) product.reorderPoint else 9}", color = Muted, fontSize = 10.sp)
                if (product.barcode.isNotBlank() || product.catalogCode.isNotBlank()) Text(listOf(product.barcode, product.catalogCode).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 10.sp)
                val unitCount = runCatching { JSONArray(product.unitVariantsJson).length() }.getOrDefault(0)
                if (unitCount > 1) Text("$unitCount وحدات/عبوات مرتبطة", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text(product.price, color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SaleRow(title: String, subtitle: String, amount: String, credit: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PointOfSale, null, tint = Green) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Surface(color = (if (credit) Orange else Green).copy(alpha = 0.12f), shape = RoundedCornerShape(7.dp)) {
                    Text(if (credit) "آجل" else "مكتمل", color = if (credit) Orange else Green, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}
