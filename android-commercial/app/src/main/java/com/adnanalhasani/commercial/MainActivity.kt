package com.adnanalhasani.commercial

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF183B35)
private val Green = Color(0xFF167D63)
private val Mint = Color(0xFFE8F5EF)
private val Canvas = Color(0xFFF5F7F6)
private val Muted = Color(0xFF71817B)
private val Orange = Color(0xFFF4A340)
private val Border = Color(0xFFE5EBE8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Canvas, surface = Color.White)) { StoreApp() } }
    }
}

private data class Product(val name: String, val category: String, val stock: Int, val price: String)
private data class SaleRecord(val number: Int, val product: String, val quantity: Int, val total: Int, val credit: Boolean)
private fun money(value: Int): String = "%,d ر.ي".format(value)
private val products = listOf(
    Product("قهوة عربية فاخرة", "مشروبات", 18, "3,500 ر.ي"),
    Product("سكر أبيض 1 كجم", "مواد غذائية", 7, "1,200 ر.ي"),
    Product("زيت نباتي 1 لتر", "مواد غذائية", 4, "2,800 ر.ي"),
    Product("مياه معدنية", "مشروبات", 32, "300 ر.ي"),
    Product("أرز بسمتي 5 كجم", "مواد غذائية", 9, "6,500 ر.ي")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreApp() {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var newSale by remember { mutableStateOf(false) }
    val sales = remember { mutableStateListOf<SaleRecord>() }
    val inventory = remember { mutableStateListOf<Product>().apply { addAll(products) } }
    var nextInvoice by remember { mutableIntStateOf(1049) }
    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Storefront, null, tint = Green)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("متجري", fontWeight = FontWeight.Bold, color = Ink)
                            Text("إدارة متجرك ببساطة", fontSize = 11.sp, color = Muted)
                        }
                    }
                },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.NotificationsNone, "الإشعارات", tint = Ink) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val nav = listOf("الرئيسية" to Icons.Default.Storefront, "المبيعات" to Icons.Default.PointOfSale, "المخزون" to Icons.Default.Inventory2, "المزيد" to Icons.Default.MoreHoriz)
                nav.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first, fontSize = 11.sp) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) FloatingActionButton(onClick = { newSale = true }, containerColor = Green, contentColor = Color.White) {
                Icon(Icons.Default.Add, "فاتورة جديدة")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> Dashboard(products = inventory, sales = sales, onNewSale = { newSale = true }, onInventory = { tab = 2 })
                1 -> SalesScreen(sales = sales, onNewSale = { newSale = true })
                2 -> InventoryScreen(inventory, query, { query = it })
                else -> MoreScreen()
            }
        }
    }
    if (newSale) SaleEntryDialog(
        inventory = inventory,
        onDismiss = { newSale = false },
        onSave = { productIndex, product, quantity, credit, unitPrice ->
            val currentProduct = inventory[productIndex]
            inventory[productIndex] = currentProduct.copy(stock = currentProduct.stock - quantity)
            sales.add(0, SaleRecord(nextInvoice, product, quantity, quantity * unitPrice, credit))
            nextInvoice += 1
            newSale = false
        }
    )
}

@Composable
private fun Dashboard(products: List<Product>, sales: List<SaleRecord>, onNewSale: () -> Unit, onInventory: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("أهلًا بك 👋", color = Color(0xFFBFD9D0), fontSize = 14.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("إليك ملخص متجرك اليوم", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Box(Modifier.size(46.dp).background(Color(0xFF31584F), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF8DE0B8))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("إجمالي المبيعات اليوم", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text("١٢٨٬٥٠٠ ر.ي", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("↑ ١٢٪ مقارنة بالأمس", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                    Spacer(Modifier.height(17.dp))
                    Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("تسجيل عملية بيع")
                    }
                }
            }
        }
        item { Heading("ملخص اليوم", "أرقام توضيحية للواجهة") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الفواتير", "٣٨", "فاتورة اليوم", Icons.Default.PointOfSale, Modifier.weight(1f))
                Metric("نقدي", "٩٦٬٠٠٠", "ر.ي", Icons.Default.AttachMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الآجل", "٣٢٬٥٠٠", "ر.ي مستحق", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("المنتجات", "١٢٤", "صنف بالمخزون", Icons.Default.Inventory2, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Heading("تنبيهات المخزون", "أصناف تحتاج إلى إعادة طلب") }
                TextButton(onClick = onInventory) { Text("عرض الكل") }
            }
        }
        items(products.filter { it.stock <= 9 }.take(3)) { ProductRow(it) }
        item { Heading("آخر المبيعات", "عمليات اليوم") }
        items(sales.take(3)) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} قطعة", money(sale.total), sale.credit) }
        item { SaleRow("فاتورة #1048", "قبل ١٠ دقائق · نقدي", "١٨٬٥٠٠ ر.ي", false) }
        item { SaleRow("فاتورة #1047", "قبل ٢٥ دقيقة · آجل", "٧٬٢٠٠ ر.ي", true) }
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
private fun SalesScreen(sales: List<SaleRecord>, onNewSale: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Heading("المبيعات والفواتير", "تابع عمليات البيع والتحصيل") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("مبيعات اليوم", "١٢٨٬٥٠٠", "ر.ي", Icons.Default.AttachMoney, Modifier.weight(1f))
                Metric("عدد الفواتير", "٣٨", "فاتورة", Icons.Default.PointOfSale, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("فاتورة بيع جديدة") } }
        item { Heading("أحدث الفواتير", "الفواتير التي أنشأتها في هذه الجلسة") }
        items(sales) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} قطعة", money(sale.total), sale.credit) }
        item { SaleRow("فاتورة #1048", "اليوم، ١٠:٤٢ ص · نقدي", "١٨٬٥٠٠ ر.ي", false) }
        item { SaleRow("فاتورة #1047", "اليوم، ١٠:٢٧ ص · آجل", "٧٬٢٠٠ ر.ي", true) }
        item { SaleRow("فاتورة #1046", "اليوم، ٠٩:٥٥ ص · نقدي", "٢٤٬٠٠٠ ر.ي", false) }
        item { SaleRow("فاتورة #1045", "اليوم، ٠٩:١٢ ص · نقدي", "٤٬٨٠٠ ر.ي", false) }
    }
}

@Composable
private fun InventoryScreen(products: List<Product>, query: String, onQuery: (String) -> Unit) {
    val filtered = products.filter { it.name.contains(query, true) || it.category.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Heading("المخزون والمنتجات", "إدارة الأصناف والكميات والأسعار") }
        item {
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("ابحث عن منتج أو تصنيف") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("إجمالي الأصناف", "١٢٤", "صنف", Icons.Default.Inventory2, Modifier.weight(1f))
                Metric("مخزون منخفض", "٠٣", "أصناف", Icons.Default.WarningAmber, Modifier.weight(1f))
            }
        }
        item { Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("إضافة منتج") } }
        item { Heading("قائمة المنتجات", "عدد النتائج: " + filtered.size) }
        items(filtered) { ProductRow(it) }
        if (filtered.isEmpty()) item { Text("لا توجد منتجات تطابق البحث.", color = Muted) }
    }
}

@Composable
private fun ProductRow(product: Product) {
    val warning = product.stock <= 9
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(45.dp).background(if (warning) Color(0xFFFFF1DF) else Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(if (warning) Icons.Default.WarningAmber else Icons.Default.Inventory2, null, tint = if (warning) Color(0xFFB96A14) else Green)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(product.category, color = Muted, fontSize = 12.sp)
                Text("المتوفر: " + product.stock + " قطع", color = if (warning) Color(0xFFB96A14) else Muted, fontSize = 11.sp)
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


@Composable
private fun SaleEntryDialog(
    inventory: List<Product>,
    onDismiss: () -> Unit,
    onSave: (Int, String, Int, Boolean, Int) -> Unit
) {
    var selected by remember { mutableIntStateOf(0) }
    var quantityText by remember { mutableStateOf("1") }
    var credit by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val product = inventory[selected.coerceIn(0, inventory.lastIndex)]
    val quantity = quantityText.toIntOrNull()
    val unitPrice = product.price.replace(",", "").replace(" ر.ي", "").toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فاتورة بيع جديدة", fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختر المنتج", color = Muted, fontSize = 12.sp)
                inventory.forEachIndexed { index, item ->
                    Surface(
                        onClick = { selected = index; error = null },
                        color = if (selected == index) Mint else Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selected == index) Green else Border)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == index, onClick = { selected = index; error = null })
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(item.price, color = Green, fontSize = 12.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter(Char::isDigit).take(4); error = null },
                    label = { Text("الكمية") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("طريقة الدفع", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !credit, onClick = { credit = false })
                    Text("نقدي", color = Ink)
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = credit, onClick = { credit = true })
                    Text("آجل", color = Ink)
                }
                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الإجمالي", color = Muted)
                    Text(if (quantity != null && quantity > 0) money(quantity * unitPrice) else "—", color = Green, fontWeight = FontWeight.Bold)
                }
                if (credit) Text("سيُسجّل المبلغ كبيع آجل (تجريبي).", color = Orange, fontSize = 12.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (quantity == null || quantity <= 0) {
                    error = "أدخل كمية صحيحة أكبر من صفر."
                } else if (quantity > product.stock) {
                    error = "الكمية المطلوبة أكبر من المخزون المتاح (${product.stock})."
                } else {
                    onSave(selected, product.name, quantity, credit, unitPrice)
                }
            }) { Text("حفظ الفاتورة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun MoreScreen() {
    val options = listOf("العملاء والمديونيات", "الموردون والمشتريات", "التقارير والأرباح", "المصروفات", "إعدادات المتجر", "النسخ الاحتياطي")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("إدارة المتجر", "الأدوات والإعدادات") }
        items(options) { label ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Settings, null, tint = Green) }
                    Spacer(Modifier.width(12.dp))
                    Text(label, modifier = Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ChevronLeft, null, tint = Muted)
                }
            }
        }
        item { Text("نسخة واجهات أولية • البيانات المعروضة تجريبية", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}
