package com.adnanalhasani.commercial

import android.content.Context

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Dashboard(products: List<Product>, sales: List<SaleRecord>, cloudMode: Boolean, context: Context, onNewSale: () -> Unit, onInventory: () -> Unit, onOpenAds: () -> Unit) {
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
        if (cloudMode) item { CommercialAdsHomePreview(context = context, onOpenAds = onOpenAds) }
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
