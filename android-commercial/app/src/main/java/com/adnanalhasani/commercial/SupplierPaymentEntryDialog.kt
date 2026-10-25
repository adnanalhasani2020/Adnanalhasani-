package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.Executors

private val SupplierPaymentWorker = Executors.newSingleThreadExecutor()
private val SupplierPaymentMain = Handler(Looper.getMainLooper())

@Composable
internal fun SupplierPaymentEntryDialog(
    context: Context,
    invoice: CommercialCloudSupplierInvoiceSummary,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val obligation = invoice.obligation
    var amount by remember(invoice.id) {
        mutableStateOf(obligation?.let { (it.amountDueMinor - it.amountPaidMinor).coerceAtLeast(0L).toString() }.orEmpty())
    }
    var evidence by remember(invoice.id) { mutableStateOf("") }
    var method by remember(invoice.id) { mutableStateOf("bank_transfer") }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val idempotencyKey = remember(invoice.id, method, evidence.trim()) {
        commercialStableIdempotencyKey("supplier-payment", listOf(obligation?.id.orEmpty(), method, evidence.trim()))
    }
    val outstanding = obligation?.let { (it.amountDueMinor - it.amountPaidMinor).coerceAtLeast(0L) } ?: 0L

    AlertDialog(
        onDismissRequest = { if (!saving && !attempted) onDismiss() },
        title = { Text("تسجيل دفعة للمورد · ${invoice.invoiceNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المستحق: ${obligation?.amountDueMinor ?: invoice.totalMinor} ${invoice.currencyCode} · المسدد: ${obligation?.amountPaidMinor ?: 0L} · المتبقي: $outstanding")
                OutlinedTextField(
                    value = amount, onValueChange = { if (!attempted) amount = it },
                    label = { Text("مبلغ الدفعة بالوحدة الصغرى") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, enabled = !attempted && !saving
                )
                OutlinedTextField(
                    value = evidence, onValueChange = { if (!attempted) evidence = it },
                    label = { Text("مرجع إثبات الدفع") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, enabled = !attempted && !saving
                )
                TextButton(enabled = !attempted && !saving, onClick = { methodMenuExpanded = true }) {
                    Text("طريقة الدفع: " + when (method) {
                        "cash" -> "نقدًا"
                        "bank_transfer" -> "تحويل بنكي"
                        "check" -> "شيك"
                        else -> "أخرى"
                    })
                }
                DropdownMenu(expanded = methodMenuExpanded, onDismissRequest = { methodMenuExpanded = false }) {
                    listOf("cash" to "نقدًا", "bank_transfer" to "تحويل بنكي", "check" to "شيك", "other" to "أخرى").forEach { option ->
                        DropdownMenuItem(text = { Text(option.second) }, onClick = { method = option.first; methodMenuExpanded = false })
                    }
                }
                if (error.isNotBlank()) Text(error)
                if (attempted && !saving) Text("أُرسلت الدفعة. أعد المحاولة بنفس البيانات والمفتاح حتى لا يتكرر القيد.")
            }
        },
        confirmButton = {
            Button(enabled = !saving && obligation != null && outstanding > 0L, onClick = {
                if (!attempted) {
                    val value = amount.trim().toLongOrNull()
                    when {
                        value == null || value <= 0L -> error = "أدخل مبلغًا موجبًا صحيحًا."
                        value > outstanding -> error = "مبلغ الدفعة يتجاوز الرصيد المستحق."
                        evidence.trim().length !in 3..200 -> error = "أدخل مرجع إثبات دفع من 3 إلى 200 حرف."
                        else -> { attempted = true; error = "" }
                    }
                }
                if (attempted) error = ""
                if (attempted && error.isBlank()) {
                    saving = true
                    val value = amount.trim().toLong()
                    SupplierPaymentWorker.execute {
                        val result = runCatching {
                            recordCommercialCloudSupplierPayment(context, obligation!!.id, value, invoice.currencyCode, method, evidence, idempotencyKey)
                        }
                        SupplierPaymentMain.post {
                            result.onSuccess { onSaved() }.onFailure {
                                saving = false
                                error = it.message ?: "تعذر تسجيل دفعة المورد. تحقق من إعداد حساب النقدية للعملة."
                            }
                        }
                    }
                }
            }) { Text(if (saving) "جارٍ الحفظ..." else if (attempted) "إعادة المحاولة" else "تسجيل الدفعة") }
        },
        dismissButton = { TextButton(enabled = !saving && !attempted, onClick = onDismiss) { Text("إلغاء") } }
    )
}
