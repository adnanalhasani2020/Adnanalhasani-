package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors

private val CloudExpenseIo = Executors.newSingleThreadExecutor()
private val CloudExpenseMain = Handler(Looper.getMainLooper())
private val CloudExpenseInk = Color(0xFF183B35)
private val CloudExpenseGreen = Color(0xFF167D63)
private val CloudExpenseMuted = Color(0xFF71817B)
private val CloudExpenseBorder = Color(0xFFE5EBE8)

private fun cloudExpenseCurrencyScale(code: String): Int =
    runCatching { Currency.getInstance(code).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)

private fun formatCloudExpenseMinor(amountMinor: Long, code: String): String =
    formatCloudExpenseMinor(BigDecimal.valueOf(amountMinor), code)

private fun formatCloudExpenseMinor(amountMinor: BigDecimal, code: String): String {
    val scale = cloudExpenseCurrencyScale(code)
    return amountMinor.movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY).toPlainString() + " " + code
}

@Composable
internal fun CommercialCloudExpensesScreen(context: Context, onBack: () -> Unit) {
    val expenses = remember { mutableStateListOf<CommercialCloudExpenseVoucher>() }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var paymentMethod by remember { mutableStateOf("cash") }
    var evidence by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("") }
    fun refresh() {
        if (loading) return
        loading = true
        error = ""
        CloudExpenseIo.execute {
            val result = runCatching { loadCommercialCloudExpenseCurrencyCode(context) to loadCommercialCloudExpenses(context) }
            CloudExpenseMain.post {
                loading = false
                result.onSuccess {
                    currency = it.first
                    expenses.clear()
                    expenses.addAll(it.second)
                }.onFailure { error = it.message ?: "تعذر تحميل سندات الصرف من الخادم." }
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val todayPrefix = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
    val today = expenses.filter { it.recordedAt.startsWith(todayPrefix) }
    val todayTotal = today.fold(BigDecimal.ZERO) { total, item -> total.add(BigDecimal.valueOf(item.amountMinor)) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("سندات الصرف السحابية", color = CloudExpenseInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("سجل مركزي مرتبط بقيود محاسبية", color = CloudExpenseMuted, fontSize = 12.sp)
                }
                IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CloudExpenseInk), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي مصروفات اليوم المؤكدة", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(formatCloudExpenseMinor(todayTotal, currency), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(today.size.toString() + " سند محفوظ", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                }
            }
        }
        item { Button(onClick = { showForm = !showForm; error = "" }, enabled = !submitting, modifier = Modifier.fillMaxWidth()) { Text(if (showForm) "إغلاق النموذج" else "إنشاء سند صرف") } }
        if (showForm) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CloudExpenseBorder), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("بيانات السند", color = CloudExpenseInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    OutlinedTextField(category, { category = it.take(120); error = "" }, label = { Text("تصنيف المصروف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(amountText, {
                        val normalized = it.filter { ch -> ch.isDigit() || ch == '.' }
                        if (normalized.count { ch -> ch == '.' } <= 1 && normalized.length <= 24) amountText = normalized
                        error = ""
                    }, label = { Text("المبلغ (" + currency + ")") }, supportingText = { Text("عدد المنازل العشرية المسموح: " + cloudExpenseCurrencyScale(currency)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("طريقة الصرف", color = CloudExpenseMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (paymentMethod == "cash") Button(onClick = { paymentMethod = "cash" }, modifier = Modifier.weight(1f)) { Text("نقدًا") }
                        else OutlinedButton(onClick = { paymentMethod = "cash" }, modifier = Modifier.weight(1f)) { Text("نقدًا") }
                        if (paymentMethod == "bank_transfer") Button(onClick = { paymentMethod = "bank_transfer" }, modifier = Modifier.weight(1f)) { Text("تحويل بنكي") }
                        else OutlinedButton(onClick = { paymentMethod = "bank_transfer" }, modifier = Modifier.weight(1f)) { Text("تحويل بنكي") }
                    }
                    OutlinedTextField(evidence, { evidence = it.take(200); error = "" }, label = { Text("مرجع إثبات الصرف") }, supportingText = { Text("رقم إيصال أو مرجع مستند") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it.take(1000); error = "" }, label = { Text("ملاحظات (اختياري)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text("يلزم ضبط حساب الصندوق/البنك للعملة. لا يوجد حفظ محلي بديل.", color = CloudExpenseMuted, fontSize = 11.sp)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(onClick = {
                        val amountMinor = runCatching {
                            val decimal = BigDecimal(amountText)
                            require(decimal.signum() > 0)
                            require(decimal.scale().coerceAtLeast(0) <= cloudExpenseCurrencyScale(currency))
                            decimal.movePointRight(cloudExpenseCurrencyScale(currency)).longValueExact()
                        }.getOrNull()
                        when {
                            currency.isBlank() -> error = "تعذر تحميل عملة المؤسسة من الخادم؛ لم يتم إرسال السند."
                            category.isBlank() -> error = "أدخل تصنيف المصروف."
                            amountMinor == null || amountMinor <= 0L -> error = "أدخل مبلغًا موجبًا وبعدد المنازل العشرية المسموح للعملة."
                            evidence.trim().length < 3 -> error = "أدخل مرجع إثبات الصرف."
                            else -> {
                                submitting = true
                                error = ""
                                val c = category.trim()
                                val e = evidence.trim()
                                val n = note.trim()
                                val m = paymentMethod
                                CloudExpenseIo.execute {
                                    val result = runCatching {
                                        val key = getOrCreateCommercialExpenseIdempotencyKey(context, c, amountMinor, currency, m, e, n)
                                        val response = recordCommercialCloudExpense(context, c, amountMinor, currency, m, e, n, key)
                                        clearCommercialExpenseIdempotencyKey(context, c, amountMinor, currency, m, e, n, key)
                                        response
                                    }
                                    val updated = if (result.isSuccess) runCatching { loadCommercialCloudExpenses(context) } else null
                                    CloudExpenseMain.post {
                                        submitting = false
                                        result.onSuccess {
                                            updated?.onSuccess { expenses.clear(); expenses.addAll(it) }
                                            showForm = false
                                            category = ""; amountText = ""; evidence = ""; note = ""
                                            error = updated?.exceptionOrNull()?.let { "تم حفظ السند وقيده؛ تعذر تحديث القائمة الآن. اضغط تحديث." }.orEmpty()
                                        }.onFailure { error = it.message ?: "تعذر تأكيد حفظ السند. أعد المحاولة بالبيانات نفسها." }
                                    }
                                }
                            }
                        }
                    }, enabled = !submitting, modifier = Modifier.fillMaxWidth()) {
                        if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("حفظ السند وترحيل القيد")
                    }
                }
            }
        }
        if (error.isNotBlank() && !showForm) item {
            Surface(color = Color(0xFFFFF0ED), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(error, color = Color(0xFF9B2C20), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
            }
        }
        item { Text("سجل سندات الصرف", color = CloudExpenseInk, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        if (loading) item { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        if (!loading && expenses.isEmpty()) item { Text("لا توجد سندات سحابية. المصروفات المحلية القديمة لا تُعرض كسجلات محاسبية.", color = CloudExpenseMuted, fontSize = 13.sp) }
        items(expenses, key = { it.id }) { voucher ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CloudExpenseBorder), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(voucher.category, color = CloudExpenseInk, fontWeight = FontWeight.SemiBold)
                            Text(voucher.recordedAt.replace("T", " ").take(16), color = CloudExpenseMuted, fontSize = 11.sp)
                        }
                        Text(formatCloudExpenseMinor(voucher.amountMinor, voucher.currencyCode), color = CloudExpenseGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text("طريقة الصرف: " + (if (voucher.paymentMethod == "cash") "نقدًا" else "تحويل بنكي") + " • الإثبات: " + voucher.evidenceReference, color = CloudExpenseMuted, fontSize = 11.sp)
                    if (voucher.note.isNotBlank()) Text(voucher.note, color = CloudExpenseInk, fontSize = 12.sp)
                    Text(if (voucher.journalEntryId.isNotBlank()) "القيد الأصلي: " + voucher.journalEntryId.take(8) + "…" else "تنبيه: السند غير مرتبط بقيد", color = if (voucher.journalEntryId.isNotBlank()) CloudExpenseGreen else Color(0xFFB42318), fontSize = 11.sp)
                    if (voucher.canceledAt.isNotBlank()) {
                        Text("ملغى محاسبيًا • " + voucher.canceledAt.replace("T", " ").take(16), color = Color(0xFFB42318), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        if (voucher.cancellationReason.isNotBlank()) Text("سبب الإلغاء: " + voucher.cancellationReason, color = CloudExpenseMuted, fontSize = 11.sp)
                        if (voucher.reversalJournalEntryId.isNotBlank()) Text("قيد العكس: " + voucher.reversalJournalEntryId.take(8) + "…", color = CloudExpenseGreen, fontSize = 11.sp)
                    } else {
                        OutlinedButton(
                            onClick = { cancelTarget = voucher; cancelReason = ""; cancelError = "" },
                            enabled = !submitting && !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إلغاء السند وعكس القيد") }
                    }
                }
            }
        }
        item { Text("لا يُنشأ السند إذا تعذر ترحيل القيد المحاسبي. الإلغاء لا يحذف السند؛ ينشئ قيد عكس مرتبطًا.", color = CloudExpenseMuted, fontSize = 11.sp) }
    }
    val target = cancelTarget
    if (target != null) AlertDialog(
        onDismissRequest = { if (!submitting) cancelTarget = null },
        title = { Text("تأكيد إلغاء سند الصرف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(target.category + " • " + formatCloudExpenseMinor(target.amountMinor, target.currencyCode), color = CloudExpenseInk)
                Text("سيبقى السند الأصلي محفوظًا، وسيُنشأ قيد عكسي. لا يمكن التراجع عن هذا الإجراء.", color = CloudExpenseMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = cancelReason,
                    onValueChange = { cancelReason = it.take(300); cancelError = "" },
                    label = { Text("سبب الإلغاء (إلزامي)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (cancelError.isNotBlank()) Text(cancelError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && cancelReason.trim().length in 3..300,
                onClick = {
                    submitting = true
                    cancelError = ""
                    val reason = cancelReason.trim()
                    CloudExpenseIo.execute {
                        val result = runCatching { cancelCommercialCloudExpenseVoucher(context, target.id, reason) }
                        val updated = if (result.isSuccess) runCatching { loadCommercialCloudExpenses(context) } else null
                        CloudExpenseMain.post {
                            submitting = false
                            result.onSuccess {
                                cancelTarget = null
                                updated?.onSuccess { expenses.clear(); expenses.addAll(it) }
                                updated?.onFailure { error = "تم إلغاء السند وعكس القيد؛ تعذر تحديث القائمة. اضغط تحديث." }
                            }.onFailure { cancelError = it.message ?: "تعذر تأكيد إلغاء السند. أعد المحاولة بالسبب نفسه." }
                        }
                    }
                }
            ) { Text(if (submitting) "جارٍ عكس القيد..." else "تأكيد الإلغاء") }
        },
        dismissButton = { TextButton(onClick = { if (!submitting) cancelTarget = null }) { Text("رجوع") } }
    )
}
