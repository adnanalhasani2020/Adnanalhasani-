package com.adnanalhasani.commercial

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.Executors

private val DebtInk = Color(0xFF183B35)
private val DebtGreen = Color(0xFF167D63)
private val DebtMuted = Color(0xFF71817B)
private val DebtIo = Executors.newSingleThreadExecutor()
private val DebtMain = Handler(Looper.getMainLooper())

private fun cloudDebtMoney(amount: Long, currency: String): String {
    val scale = runCatching { Currency.getInstance(currency).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)
    val value = java.math.BigDecimal.valueOf(amount).movePointLeft(scale)
        .setScale(scale, java.math.RoundingMode.UNNECESSARY)
    return NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = scale
        maximumFractionDigits = scale
    }.format(value) + " " + currency
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudDebtScreen(context: Context, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<CommercialCloudReceivable>>(emptyList()) }
    var selected by remember { mutableStateOf<CommercialCloudReceivable?>(null) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("cash") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var receiptText by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        busy = true
        error = ""
        DebtIo.execute {
            val result = runCatching { loadCommercialCloudReceivables(context) }
            DebtMain.post {
                busy = false
                result.onSuccess { rows = it }
                    .onFailure { error = it.message ?: "تعذر تحميل مديونيات العملاء من الخادم." }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("مديونيات العملاء", color = DebtInk, fontWeight = FontWeight.Bold)
                    Text("أرصدة مؤكدة من قاعدة البيانات", color = DebtMuted, fontSize = 11.sp)
                } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(Icons.Default.Refresh, "تحديث") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF5F7F6)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = DebtInk), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("الفواتير ذات الرصيد المستحق", color = Color(0xFFBFD9D0), fontSize = 12.sp)
                        Text(if (busy) "…" else rows.size.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("لا يتم تعديل الرصيد محليًا؛ كل تحصيل يُعتمد من الخادم.", color = Color(0xFFBFD9D0), fontSize = 11.sp)
                    }
                }
            }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEFEC))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error, color = Color(0xFF9C3028))
                        Button(onClick = { refresh++ }, enabled = !busy) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (message.isNotBlank()) item { Text(message, color = DebtGreen, fontSize = 12.sp) }
            if (busy && rows.isEmpty()) item { CircularProgressIndicator(color = DebtGreen) }
            if (!busy && error.isBlank() && rows.isEmpty()) item {
                Text("لا توجد فواتير آجلة مستحقة لهذا النشاط.", color = DebtMuted)
            }
            items(rows, key = { it.saleId }) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(row.customerName, color = DebtInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("فاتورة #${row.receiptNumber}", color = DebtMuted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإجمالي", color = DebtMuted, fontSize = 12.sp)
                            Text(cloudDebtMoney(row.totalMinor, row.currencyCode), color = DebtInk, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("صافي المحصّل بعد الاسترداد", color = DebtMuted, fontSize = 12.sp)
                            Text(cloudDebtMoney(row.netPaidMinor, row.currencyCode), color = DebtGreen)
                        }
                        if (row.refundedMinor > 0L) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("المبلغ المسترد نقديًا", color = DebtMuted, fontSize = 12.sp)
                                Text(cloudDebtMoney(row.refundedMinor, row.currencyCode), color = DebtMuted)
                            }
                        }
                        if (row.creditedMinor > 0L) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("خصم المرتجعات المعتمدة", color = DebtMuted, fontSize = 12.sp)
                                Text(cloudDebtMoney(row.creditedMinor, row.currencyCode), color = DebtGreen)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المتبقي", color = DebtMuted, fontSize = 12.sp)
                            Text(cloudDebtMoney(row.dueMinor, row.currencyCode), color = Color(0xFFB96A14), fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = {
                            selected = row
                            amount = row.dueMinor.toString()
                            method = "cash"
                            error = ""
                            message = ""
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("تسجيل تحصيل") }
                    }
                }
            }
        }
    }

    val target = selected
    if (target != null) AlertDialog(
        onDismissRequest = { if (!busy) selected = null },
        title = { Text("تحصيل من فاتورة #${target.receiptNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(target.customerName, color = DebtInk, fontWeight = FontWeight.SemiBold)
                Text("المتبقي: ${cloudDebtMoney(target.dueMinor, target.currencyCode)}", color = DebtMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(18) },
                    enabled = !busy,
                    label = { Text("مبلغ التحصيل بوحدة الصرف الصغرى") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("طريقة الدفع", color = DebtMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("cash" to "نقدي", "bank_transfer" to "تحويل", "card" to "بطاقة", "other" to "أخرى").forEach { (value, label) ->
                        FilterChip(selected = method == value, onClick = { method = value }, enabled = !busy, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFF9C3028), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (amount.toLongOrNull() ?: 0L) in 1L..target.dueMinor,
                onClick = {
                    val value = amount.toLongOrNull() ?: return@Button
                    val selectedMethod = method
                    busy = true
                    error = ""
                    DebtIo.execute {
                        val result = runCatching {
                            val payment = recordCommercialCloudCustomerPayment(context, target.saleId, value, selectedMethod)
                            val paymentId = payment.optString("payment_id")
                            require(paymentId.isNotBlank()) { "أكد الخادم التحصيل دون إرجاع مرجع الدفعة." }
                            val receiptResult = runCatching { loadCommercialCloudCustomerReceipt(context, paymentId) }
                            val receipt = receiptResult.getOrNull()
                            if (receipt == null || receipt.optLong("receipt_number", 0L) <= 0L) {
                                "RECEIPT_UNAVAILABLE:$paymentId"
                            } else {
                            val sale = receipt.optJSONObject("sale")
                            val customer = sale?.optJSONObject("customer")?.optString("display_name").orEmpty().ifBlank { target.customerName }
                            val saleNumber = sale?.optString("receipt_number").orEmpty().ifBlank { target.receiptNumber }
                            val receiptNumber = receipt.optLong("receipt_number", 0L)
                            val paidMinor = receipt.optLong("amount_minor", value)
                            val currencyCode = receipt.optString("currency_code").ifBlank { target.currencyCode }
                            val createdAt = receipt.optString("created_at").replace('T', ' ').take(19)
                            val methodLabel = when (receipt.optString("payment_method_type", method)) {
                                "cash" -> "نقدي"
                                "bank_transfer" -> "تحويل بنكي"
                                "card" -> "بطاقة"
                                else -> "أخرى"
                            }
                            "سند قبض رقم RC-$receiptNumber\nالعميل: $customer\nفاتورة رقم: $saleNumber\nالمبلغ المقبوض: ${cloudDebtMoney(paidMinor, currencyCode)}\nطريقة الدفع: $methodLabel\nالتاريخ: $createdAt\nمرجع الدفعة: $paymentId"
                            }
                        }
                        DebtMain.post {
                            busy = false
                            result.onSuccess { text ->
                                selected = null
                                if (text.startsWith("RECEIPT_UNAVAILABLE:")) {
                                    receiptText = ""
                                    message = "تم تسجيل التحصيل وربطه محاسبيًا. تعذر تحميل رقم السند الرسمي الآن؛ حدّث السجل لاحقًا ولا تكرر التحصيل. مرجع الدفعة: ${text.substringAfter(':')}"
                                } else {
                                    receiptText = text
                                    message = "تم تسجيل التحصيل وإصدار سند قبض مرقّم مرتبط بالدفعة المحاسبية."
                                }
                                refresh++
                            }.onFailure { error = it.message ?: "تعذر تسجيل التحصيل." }
                        }
                    }
                }
            ) { Text(if (busy) "جارٍ الحفظ..." else "تأكيد التحصيل") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) selected = null }) { Text("إلغاء") } }
    )

    if (receiptText.isNotBlank()) AlertDialog(
        onDismissRequest = { receiptText = "" },
        title = { Text("سند قبض") },
        text = { Text(receiptText, color = DebtInk) },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "سند قبض - متجري")
                    putExtra(Intent.EXTRA_TEXT, receiptText)
                }
                context.startActivity(Intent.createChooser(intent, "مشاركة سند القبض").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("مشاركة / طباعة") }
        },
        dismissButton = { TextButton(onClick = { receiptText = "" }) { Text("إغلاق") } }
    )
}
