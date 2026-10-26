package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

private val HistoryInk = Color(0xFF183B35)
private val HistoryGreen = Color(0xFF167D63)
private val HistoryMuted = Color(0xFF71817B)
private val HistoryBorder = Color(0xFFE5EBE8)
private val historyExecutor = Executors.newSingleThreadExecutor()
private val historyMain = Handler(Looper.getMainLooper())
private fun historyMoney(value: Long, currency: String) = String.format(Locale.US, "%,d %s", value, currency)
private fun historyDate(value: String, timezone: String? = null): String = runCatching {
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { Instant.parse(value) }
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(timezone?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()).format(instant)
}.getOrDefault(value.replace("T", " ").take(16))
private fun orderStatus(s: String) = when (s) {
    "pending" -> "بانتظار مراجعة المؤسسة"; "confirmed" -> "معتمد"; "in_progress" -> "قيد التنفيذ"
    "fulfilled" -> "مكتمل التنفيذ"; "cancelled" -> "ملغى"; "rejected" -> "مرفوض"; else -> "غير معروف (" + s + ")"
}
private fun paymentStatus(s: String) = when (s) {
    "unpaid" -> "غير مدفوع"; "partially_paid" -> "مدفوع جزئيًا"; "paid" -> "مدفوع"
    "credit" -> "آجل"; "refunded" -> "مسترد"; else -> "غير معروف (" + s + ")"
}
private fun bookingStatus(s: String) = when (s) {
    "requested" -> "بانتظار تأكيد المؤسسة"; "confirmed" -> "مؤكد"; "checked_in" -> "تم تسجيل الوصول"
    "completed" -> "مكتمل"; "cancelled" -> "ملغى"; "rejected" -> "مرفوض"; "no_show" -> "لم يحضر"
    else -> "غير معروف (" + s + ")"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerPortalHistoryScreen(context: Context, onBack: () -> Unit) {
    var history by remember { mutableStateOf<CustomerPortalHistory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedOrderId by remember { mutableStateOf<String?>(null) }
    var cancelTarget by remember { mutableStateOf<CustomerPortalHistoryBooking?>(null) }
    var cancelOrderTarget by remember { mutableStateOf<CustomerPortalHistoryOrder?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var cancellingOrder by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        loading = true; error = ""
        historyExecutor.execute {
            val result = runCatching { loadCustomerPortalHistory(context) }
            historyMain.post {
                loading = false
                result.onSuccess { loaded ->
                    history = loaded
                    if (selectedOrderId != null && loaded.orders.none { it.id == selectedOrderId }) selectedOrderId = null
                }.onFailure { error = it.message ?: "تعذر تحميل سجل الطلبات والحجوزات من الخادم." }
            }
        }
    }
    val data = history
    val selected = data?.orders?.firstOrNull { it.id == selectedOrderId }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column {
                Text(if (selected != null) "تفاصيل الطلب" else "طلباتي وحجوزاتي", color = HistoryInk, fontWeight = FontWeight.Bold)
                Text("سجل محدث مباشرة من الخادم", color = HistoryMuted, fontSize = 11.sp)
            } },
            navigationIcon = { IconButton(onClick = { if (selectedOrderId != null) selectedOrderId = null else onBack() }) { Icon(Icons.Default.ArrowBack, "رجوع", tint = HistoryInk) } },
            actions = { IconButton(onClick = { refresh++ }, enabled = !loading && !cancelling) { Icon(Icons.Default.Refresh, "تحديث", tint = HistoryInk) } }
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) { Text(error); TextButton(onClick = { refresh++ }) { Text("إعادة المحاولة") } }
                }
            }
            if (notice.isNotBlank()) item { Text(notice, color = HistoryGreen) }
            if (selected != null) {
                item { HistoryCard {
                    Text("طلب رقم " + selected.orderNumber.ifBlank { selected.id.take(8) }, color = HistoryInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(selected.organizationName, color = HistoryMuted)
                    HistoryField("تاريخ الطلب", historyDate(selected.createdAt))
                    HistoryField("الحالة التشغيلية", orderStatus(selected.status))
                    HistoryField("حالة الدفع", paymentStatus(selected.paymentStatus))
                    HistoryField("نوع الطلب", when (selected.orderType) { "booking" -> "حجز"; "service" -> "خدمة"; else -> "شراء" })
                    HorizontalDivider(color = HistoryBorder)
                    HistoryField("المجموع", historyMoney(selected.subtotalMinor, selected.currencyCode))
                    HistoryField("الخصم", historyMoney(selected.discountMinor, selected.currencyCode))
                    HistoryField("الضريبة", historyMoney(selected.taxMinor, selected.currencyCode))
                    HistoryField("الإجمالي المحفوظ", historyMoney(selected.totalMinor, selected.currencyCode), true)
                    if (selected.note.isNotBlank()) HistoryField("ملاحظتك", selected.note)
                    if (selected.reservedFrom.isNotBlank()) {
                        HistoryField("بداية الفترة", historyDate(selected.reservedFrom))
                        HistoryField("نهاية الفترة", historyDate(selected.reservedUntil))
                    }
                    if (selected.status == "pending" && selected.paymentStatus in setOf("unpaid", "credit") && selected.orderType != "booking") {
                        OutlinedButton(onClick = { cancelOrderTarget = selected }, enabled = !cancellingOrder && !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("إلغاء الطلب قبل اعتماد المؤسسة")
                        }
                    }
                } }
                item { Text("بنود الطلب والأسعار المحفوظة", color = HistoryInk, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                if (selected.items.isEmpty()) item { Text("لا توجد بنود مسجلة.", color = HistoryMuted) }
                items(selected.items, key = { it.id }) { line -> HistoryCard {
                    Text(line.name, color = HistoryInk, fontWeight = FontWeight.SemiBold)
                    Text("النوع: " + (if (line.itemType == "service") "خدمة" else "منتج") + " · الكمية: " + line.quantity, color = HistoryMuted, fontSize = 12.sp)
                    HistoryField("سعر الوحدة المحفوظ", historyMoney(line.unitPriceMinor, selected.currencyCode))
                    HistoryField("إجمالي البند المحفوظ", historyMoney(line.lineTotalMinor, selected.currencyCode), true)
                } }
                item { Text("الطلب المحفوظ لا يعني وحده اعتماد البيع أو حجز المخزون أو تحصيل المبلغ.", color = HistoryMuted, fontSize = 12.sp) }
            } else {
                item { HistoryCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = HistoryGreen); Spacer(Modifier.width(10.dp))
                        Column { Text("سجل حسابك", color = HistoryInk, fontWeight = FontWeight.Bold); Text("البيانات مرتبطة بحسابك المصادق عليه فقط.", color = HistoryMuted, fontSize = 12.sp) }
                    }
                } }
                item { Text("الطلبات", color = HistoryInk, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                item { HistoryCard {
                    Text("حالة الطلب المبدئي", color = HistoryInk, fontWeight = FontWeight.Bold)
                    Text("إرسال الطلب لا يحجز المخزون ولا يُكمل البيع أو يثبت التحصيل. تبقى الكمية غير مضمونة حتى تراجع المؤسسة الطلب وتؤكد التوفر عبر إجراءات المتجر.", color = HistoryMuted, fontSize = 12.sp)
                } }
                if (!loading && error.isBlank() && data?.orders.isNullOrEmpty()) item { Text("لا توجد طلبات سابقة.", color = HistoryMuted) }
                items(data?.orders.orEmpty(), key = { it.id }) { order ->
                    HistoryCard(modifier = Modifier.clickable { selectedOrderId = order.id }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ReceiptLong, null, tint = HistoryGreen); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("طلب #" + order.orderNumber.ifBlank { order.id.take(8) }, color = HistoryInk, fontWeight = FontWeight.Bold)
                                Text(order.organizationName, color = HistoryMuted, fontSize = 11.sp)
                            }
                            Text(historyMoney(order.totalMinor, order.currencyCode), color = HistoryGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        HistoryField("التاريخ", historyDate(order.createdAt))
                        HistoryField("الحالة التشغيلية", orderStatus(order.status))
                        HistoryField("حالة الدفع", paymentStatus(order.paymentStatus))
                        Text("اضغط لعرض البنود والأسعار", color = HistoryGreen, fontSize = 12.sp)
                    }
                }
                item { Text("الحجوزات", color = HistoryInk, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                if (!loading && error.isBlank() && data?.bookings.isNullOrEmpty()) item { Text("لا توجد حجوزات سابقة.", color = HistoryMuted) }
                items(data?.bookings.orEmpty(), key = { "booking-" + it.id }) { b ->
                    val serviceName = data?.orders?.firstOrNull { order -> order.id == b.orderId }?.items?.joinToString("، ") { line -> line.name }.orEmpty()
                    HistoryCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Event, null, tint = HistoryGreen); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("حجز #" + b.bookingNumber.ifBlank { b.id.take(8) }, color = HistoryInk, fontWeight = FontWeight.Bold)
                                Text(b.organizationName, color = HistoryMuted, fontSize = 11.sp)
                            }
                            Text(historyMoney(b.priceMinor, b.currencyCode), color = HistoryGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        HistoryField("الحالة", bookingStatus(b.status))
                        HistoryField("الخدمة", serviceName.ifBlank { "راجع تفاصيل الطلب المرتبط" })
                        HistoryField("المورد", b.resourceName.ifBlank { "غير محدد" })
                        HistoryField("النوع", b.resourceType.ifBlank { "غير محدد" })
                        HistoryField("البداية (" + b.timezone + ")", historyDate(b.startsAt, b.timezone))
                        HistoryField("النهاية (" + b.timezone + ")", historyDate(b.endsAt, b.timezone))
                        HistoryField("المدفوع", historyMoney(b.paidMinor, b.currencyCode))
                        if (b.notes.isNotBlank()) HistoryField("ملاحظات", b.notes)
                        if (b.cancellationReason.isNotBlank()) HistoryField("سبب الإلغاء", b.cancellationReason)
                        if (b.status == "requested" && runCatching { OffsetDateTime.parse(b.startsAt).toInstant().isAfter(Instant.now()) }.getOrDefault(false)) {
                            OutlinedButton(onClick = { cancelTarget = b }, enabled = !cancelling && !loading, modifier = Modifier.fillMaxWidth()) { Text("طلب إلغاء الحجز") }
                        }
                    }
                }
                item { Text("الإلغاء متاح فقط للحجز الذي ما زال بانتظار المراجعة ولم يبدأ موعده. الحجوزات المؤكدة تتطلب مراجعة المؤسسة.", color = HistoryMuted, fontSize = 12.sp) }
            }
        }
    }
    cancelOrderTarget?.let { order ->
        AlertDialog(
            onDismissRequest = { if (!cancellingOrder) cancelOrderTarget = null },
            title = { Text("إلغاء الطلب المبدئي") },
            text = { Text("سيُلغى الطلب رقم " + order.orderNumber + " فقط إذا كان لا يزال بانتظار المراجعة وغير مدفوع. لا يمكن من هذه الشاشة إلغاء طلب معتمد أو مدفوع، ولا يؤدي الإلغاء إلى حركة مخزون لأن الطلب المبدئي لا يحجز المخزون.") },
            confirmButton = {
                TextButton(enabled = !cancellingOrder, onClick = {
                    cancellingOrder = true
                    historyExecutor.execute {
                        val result = runCatching { cancelCustomerPortalOrder(context, order.id, "Cancelled by customer") }
                        historyMain.post {
                            cancellingOrder = false
                            cancelOrderTarget = null
                            result.onSuccess {
                                notice = if (it == "already_cancelled")
                                    "الطلب رقم " + order.orderNumber + " ملغى بالفعل."
                                else "تم تأكيد إلغاء الطلب رقم " + order.orderNumber + " من الخادم."
                                refresh++
                            }.onFailure {
                                error = it.message ?: "لم يتأكد إلغاء الطلب. حدّث السجل قبل إعادة المحاولة."
                            }
                        }
                    }
                }) { Text(if (cancellingOrder) "جارٍ الإلغاء..." else "تأكيد الإلغاء") }
            },
            dismissButton = {
                TextButton(enabled = !cancellingOrder, onClick = { cancelOrderTarget = null }) { Text("رجوع") }
            }
        )
    }

    cancelTarget?.let { booking ->
        AlertDialog(
            onDismissRequest = { if (!cancelling) cancelTarget = null },
            title = { Text("تأكيد إلغاء الحجز") },
            text = { Text("سيعيد الخادم التحقق من ملكية الحجز وحالته ووقت بدايته قبل الإلغاء.") },
            confirmButton = { TextButton(enabled = !cancelling, onClick = {
                cancelling = true
                historyExecutor.execute {
                    val result = runCatching { cancelCustomerPortalBooking(context, booking.id, "Cancelled by customer") }
                    historyMain.post {
                        cancelling = false; cancelTarget = null
                        result.onSuccess { notice = "تم تأكيد إلغاء الحجز رقم " + booking.bookingNumber; refresh++ }
                            .onFailure { error = it.message ?: "لم يتأكد إلغاء الحجز. حدّث السجل قبل إعادة المحاولة." }
                    }
                }
            }) { Text(if (cancelling) "جارٍ الإلغاء..." else "تأكيد الإلغاء") } },
            dismissButton = { TextButton(enabled = !cancelling, onClick = { cancelTarget = null }) { Text("رجوع") } }
        )
    }
}

@Composable
private fun HistoryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, HistoryBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
@Composable
private fun HistoryField(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = HistoryMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(value, color = if (emphasize) HistoryGreen else HistoryInk, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
    }
}
