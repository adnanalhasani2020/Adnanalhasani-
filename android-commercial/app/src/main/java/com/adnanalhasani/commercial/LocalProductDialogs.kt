package com.adnanalhasani.commercial

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.Executors
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
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)

@Composable
internal fun SaleEntryDialog(
    inventory: List<Product>,
    cloudMode: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Int, String, Int, Boolean, Int, String, Int, String) -> Unit
) {
    if (inventory.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("لا توجد أصناف") },
            text = { Text("أضف صنفًا إلى المخزون أو استورد صنفًا من دليل الأصناف قبل إنشاء فاتورة.") },
            confirmButton = { Button(onClick = onDismiss) { Text("حسنًا") } }
        )
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var selectedUnitIndex by remember(selected) { mutableIntStateOf(-1) }
    var quantityText by remember { mutableStateOf("1") }
    var priceOverrideText by remember(selected, selectedUnitIndex) { mutableStateOf("") }
    var credit by remember { mutableStateOf(false) }
    var customer by remember { mutableStateOf("") }
    var unitExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val product = inventory[selected.coerceIn(0, inventory.lastIndex)]
    val saleUnits = localSaleUnits(product)
    val defaultUnitIndex = saleUnits.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    val effectiveUnitIndex = selectedUnitIndex.takeIf { it in saleUnits.indices } ?: defaultUnitIndex
    val selectedUnit = saleUnits[effectiveUnitIndex]
    val basePrice = product.price.replace(",", "").replace(" ر.ي", "").toIntOrNull() ?: 0
    val suggestedUnitPrice = runCatching {
        BigDecimal.valueOf(basePrice.toLong()).multiply(BigDecimal.valueOf(selectedUnit.quantityInBase))
            .setScale(0, java.math.RoundingMode.HALF_UP).intValueExact()
    }.getOrDefault(0)
    val quantity = quantityText.toIntOrNull()
    val unitPrice = priceOverrideText.toIntOrNull() ?: suggestedUnitPrice
    val baseQuantityExact = runCatching {
        Math.multiplyExact((quantity ?: 0).toLong(), selectedUnit.quantityInBase)
    }.getOrNull()
    val baseQuantity = baseQuantityExact?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    val quantityIsWholeBaseUnits = baseQuantityExact != null && baseQuantityExact in 1L..Int.MAX_VALUE.toLong()
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
                ExposedDropdownMenuBox(expanded = unitExpanded, onExpandedChange = { unitExpanded = it }) {
                    OutlinedTextField(
                        value = "${selectedUnit.label} × ${selectedUnit.quantityInBase} ${product.unitCode} • ${selectedUnit.barcode.ifBlank { "بدون باركود" }}",
                        onValueChange = {}, readOnly = true, label = { Text("وحدة البيع / العبوة") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                        saleUnits.forEachIndexed { index, unit ->
                            DropdownMenuItem(
                                text = { Text("${unit.label} × ${unit.quantityInBase} ${product.unitCode}${if (unit.barcode.isNotBlank()) " • ${unit.barcode}" else ""}") },
                                onClick = { selectedUnitIndex = index; priceOverrideText = ""; unitExpanded = false; error = null }
                            )
                        }
                    }
                }
                if (selectedUnit.imageUrl.startsWith("https://", true)) {
                    AsyncImage(model=selectedUnit.imageUrl,contentDescription=selectedUnit.label,modifier=Modifier.fillMaxWidth().heightIn(max=130.dp),contentScale=ContentScale.Fit)
                }
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter(Char::isDigit).take(4); error = null },
                    label = { Text("الكمية بوحدة البيع المختارة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceOverrideText.ifBlank { suggestedUnitPrice.toString() },
                    onValueChange = { if (!cloudMode) priceOverrideText = it.filter(Char::isDigit).take(9); error = null },
                    readOnly = cloudMode,
                    label = { Text(if (cloudMode) "سعر البيع المعتمد من قاعدة البيانات" else "سعر الوحدة/العبوة (اقتراح نسبي، عدّله حسب سعرك)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!cloudMode && credit) OutlinedTextField(value = customer, onValueChange = { customer = it; error = null }, label = { Text("اسم العميل المدين") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!cloudMode) {
                    Text("طريقة الدفع", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !credit, onClick = { credit = false })
                        Text("نقدي", color = Ink)
                        Spacer(Modifier.width(12.dp))
                        RadioButton(selected = credit, onClick = { credit = true })
                        Text("آجل", color = Ink)
                    }
                } else {
                    Text("البيع السحابي نقدي حاليًا؛ السعر يُقرأ من العرض المحفوظ على الخادم.", color = Muted, fontSize = 12.sp)
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
                } else if (!quantityIsWholeBaseUnits) {
                    error = "تحويل هذه العبوة ينتج كمية كسرية، والمخزون المحلي الحالي يدعم الكميات الصحيحة فقط."
                } else if (baseQuantity > product.stock) {
                    error = "الكمية المطلوبة تعادل $baseQuantity من وحدة الأساس، والمتاح ${product.stock}."
                } else if (unitPrice <= 0) {
                    error = "أدخل سعر بيع أكبر من صفر."
                } else if (cloudMode && unitPrice != suggestedUnitPrice) {
                    error = "سعر العبوة لا يطابق السعر النسبي المعتمد من الخادم."
                } else if (credit && customer.isBlank()) {
                    error = "أدخل اسم العميل للبيع الآجل."
                } else {
                    onSave(selected, product.name, quantity, credit, unitPrice, if (credit) customer.trim() else "", baseQuantity, selectedUnit.code)
                }
            }) { Text("حفظ الفاتورة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
internal fun EditProductDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(product) { mutableStateOf(product.name) }
    var category by remember(product) { mutableStateOf(product.category) }
    var stockText by remember(product) { mutableStateOf(product.stock.toString()) }
    var priceText by remember(product) { mutableStateOf(product.price.replace(",", "").replace(" ر.ي", "")) }
    var costText by remember(product) { mutableStateOf(if (product.costPrice > 0) product.costPrice.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember(product) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل المنتج", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("تاريخ الإدخال: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(product.createdAt))} • الموظف/المستخدم: ${product.createdBy.ifBlank { "محلي / غير محدد" }}", color = Muted, fontSize = 11.sp)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("اسم المنتج") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it; error = null }, label = { Text("التصنيف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stockText, { stockText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("الكمية الحالية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priceText, { priceText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("سعر البيع للوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(costText, { costText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("تكلفة شراء الوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val stock = stockText.toIntOrNull()
                val price = priceText.toIntOrNull()
                val cost = costText.toIntOrNull()
                when {
                    name.isBlank() -> error = "أدخل اسم المنتج."
                    category.isBlank() -> error = "أدخل تصنيف المنتج."
                    stock == null || stock < 0 -> error = "أدخل كمية صحيحة."
                    price == null || price <= 0 -> error = "أدخل سعر بيع أكبر من صفر."
                    cost == null || cost < 0 -> error = "أدخل تكلفة شراء صحيحة (يمكن أن تكون صفرًا)."
                    else -> onSave(name.trim(), category.trim(), stock, price, cost)
                }
            }) { Text("حفظ التعديلات") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("تأكيد حذف المنتج") },
        text = { Text("هل تريد حذف «${product.name}» من المخزون؟ لا يمكن التراجع عن هذا الإجراء.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text("حذف المنتج", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
    )
}

@Composable
internal fun AddProductDialog(
    cloudMode: Boolean = false,
    cloudCategories: List<CommercialMasterDataRow> = emptyList(),
    cloudUnits: List<CommercialUnitOption> = emptyList(),
    initialName: String = "",
    initialUnitCode: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Int, Int, Int, String, String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var category by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf("") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var unitCode by remember(cloudUnits, initialUnitCode) { mutableStateOf(initialUnitCode.takeIf { code -> cloudUnits.any { it.code == code } } ?: cloudUnits.firstOrNull { it.code == "each" }?.code ?: cloudUnits.firstOrNull()?.code.orEmpty()) }
    var unitMenuExpanded by remember { mutableStateOf(false) }
    var unitName by remember(cloudUnits, initialUnitCode) { mutableStateOf(cloudUnits.firstOrNull { it.code == initialUnitCode }?.label ?: "قطعة") }
    var reorderPointText by remember { mutableStateOf("0") }
    var stockText by remember { mutableStateOf("0") }
    var priceText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة منتج جديد", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("اسم المنتج") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!cloudMode) OutlinedTextField(category, { category = it; error = null }, label = { Text("التصنيف المحلي") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cloudMode) {
                    val categoryLabel = cloudCategories.firstOrNull { it.id == categoryId }?.let { "${it.code} — ${it.label}" } ?: "بدون تصنيف"
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { categoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("تصنيف الصنف: $categoryLabel") }
                        DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("بدون تصنيف") }, onClick = { categoryId = ""; categoryMenuExpanded = false })
                            cloudCategories.filter { it.kind == "active" }.forEach { item ->
                                DropdownMenuItem(text = { Text("${item.code} — ${item.label}") }, onClick = { categoryId = item.id; categoryMenuExpanded = false })
                            }
                        }
                    }
                    if (cloudCategories.none { it.kind == "active" }) Text("لا توجد تصنيفات نشطة. أضف تصنيفًا من دليل المستودعات والمجموعات أولًا.", color = Muted, fontSize = 11.sp)
                }
                if (cloudMode) {
                    val selectedUnit = cloudUnits.firstOrNull { it.code == unitCode }
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { unitMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("وحدة الأساس: ${selectedUnit?.label ?: "اختر وحدة قياس"}")
                        }
                        DropdownMenu(expanded = unitMenuExpanded, onDismissRequest = { unitMenuExpanded = false }) {
                            cloudUnits.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text("${unit.label} · ${unit.dimension} (${unit.code})") },
                                    onClick = { unitCode = unit.code; unitName = unit.label; unitMenuExpanded = false; error = null }
                                )
                            }
                        }
                    }
                    if (cloudUnits.isEmpty()) Text("تعذر تحميل وحدات القياس النشطة. أعد المحاولة بعد التحقق من الاتصال.", color = Muted, fontSize = 11.sp)
                } else OutlinedTextField(unitName, { unitName = it; error = null }, label = { Text("الوحدة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cloudMode) OutlinedTextField(reorderPointText, { reorderPointText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("حد التنبيه للمخزون المنخفض") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stockText, { stockText = it.filter(Char::isDigit).take(7); error = null }, label = { Text(if (cloudMode) "الكمية الافتتاحية (بوحدة الأساس)" else "الكمية الافتتاحية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priceText, { priceText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("سعر البيع للوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!cloudMode) OutlinedTextField(costText, { costText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("تكلفة شراء الوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cloudMode) Text("سيُحفظ الصنف ووحدة القياس المرتبطة ورمزها والتصنيف المختار والكمية الافتتاحية ضمن عملية ذرية واحدة.", color = Muted, fontSize = 11.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val stock = stockText.toIntOrNull()
                val price = priceText.toIntOrNull()
                val cost = costText.toIntOrNull()
                val reorderPoint = reorderPointText.toIntOrNull()
                when {
                    name.isBlank() -> error = "أدخل اسم المنتج."
                    !cloudMode && category.isBlank() -> error = "أدخل تصنيف المنتج."
                    unitName.isBlank() -> error = "أدخل اسم الوحدة."
                    stock == null || stock < 0 -> error = "أدخل كمية صحيحة."
                    price == null || price <= 0 -> error = "أدخل سعر بيع أكبر من صفر."
                    !cloudMode && (cost == null || cost < 0) -> error = "أدخل تكلفة شراء صحيحة (يمكن أن تكون صفرًا)."
                    cloudMode && (reorderPoint == null || reorderPoint < 0) -> error = "أدخل حد تنبيه صحيحًا."
                    cloudMode && cloudUnits.none { it.code == unitCode && unitCode.isNotBlank() } -> error = "اختر وحدة قياس نشطة."
                    cloudMode && categoryId.isNotBlank() && cloudCategories.none { it.id == categoryId && it.kind == "active" } -> error = "اختر تصنيفًا نشطًا من القائمة."
                    else -> onSave(name.trim(), category.trim(), if (cloudMode) cloudUnits.firstOrNull { it.code == unitCode }?.label.orEmpty() else unitName.trim(), stock, price, cost ?: 0, reorderPoint ?: 0, if (cloudMode) categoryId else "", if (cloudMode) unitCode else "")
                }
            }) { Text("حفظ المنتج") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
