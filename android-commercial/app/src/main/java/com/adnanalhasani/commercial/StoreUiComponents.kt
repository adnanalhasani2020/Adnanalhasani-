package com.adnanalhasani.commercial

import org.json.JSONArray
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Metric(title: String, value: String, note: String, icon: ImageVector, modifier: Modifier = Modifier) {
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
internal fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun ProductRow(product: Product, onClick: () -> Unit = {}) {
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
internal fun SaleRow(title: String, subtitle: String, amount: String, credit: Boolean) {
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
