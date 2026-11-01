package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.Executors

private val PurchaseReceiptWorker = Executors.newSingleThreadExecutor()
private val PurchaseReceiptMain = Handler(Looper.getMainLooper())

@Composable
internal fun PurchaseReceiptEntryDialog(
    context: Context,
    order: CommercialCloudPurchaseDocument,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val quantities = remember(order.id) {
        mutableStateMapOf<String, String>().apply { order.lines.forEach { put(it.id, "") } }
    }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var idempotencyKey by remember(order.id) { mutableStateOf("") }
    val hasUnmappedRemaining = order.lines.any { line ->
        val ordered = line.quantityOrdered.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val received = line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        received < ordered && line.offeringId.isNullOrBlank()
    }
    val eligibleLines = if (hasUnmappedRemaining) emptyList() else order.lines.filter { line ->
        val ordered = line.quantityOrdered.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val received = line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        line.offeringId != null && received < ordered
    }

    AlertDialog(
        onDismissRequest = { if (!saving && !attempted) onDismiss() },
        title = { Text("استلام بضاعة · ${order.orderNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سيُحدّث الاستلام رصيد المخزون وسجل الحركة وأمر الشراء في معاملة واحدة.")
                Text("موقع الاستلام: ${order.receiveLocationId ?: "غير محدد"}")
                if (eligibleLines.isEmpty()) {
                    Text(if (hasUnmappedRemaining) "لا يمكن الاستلام بأمان: يوجد بند متبقٍ غير مربوط بعنصر مخزون. اربط كل البنود المتبقية أولًا." else "لا توجد كميات متبقية للاستلام.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(eligibleLines, key = { it.id }) { line ->
                            val ordered = line.quantityOrdered.toBigDecimalOrNull()
                            val received = line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                            val remaining = if (ordered != null) ordered.subtract(received).max(java.math.BigDecimal.ZERO).stripTrailingZeros().toPlainString() else "غير معروف"
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${line.lineNumber}. ${line.description}")
                                Text("مطلوب: ${line.quantityOrdered} · مستلم: ${line.quantityReceived.ifBlank { "0" }} · متبقٍ: $remaining")
                                OutlinedTextField(
                                    value = quantities[line.id].orEmpty(),
                                    onValueChange = { if (!attempted) quantities[line.id] = it },
                                    label = { Text("الكمية المستلمة الآن") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !saving && !attempted
                                )
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error)
                if (attempted && !saving) Text("أُرسلت هذه العملية. أعد المحاولة بنفس البيانات والمفتاح حتى تتأكد من النتيجة؛ لا تعدّل الكميات بعد إرسالها.")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attempted) {
                    Button(enabled = !saving, onClick = {
                        saving = true
                        error = ""
                        submitPurchaseReceipt(context, order, eligibleLines, quantities.toMap(), idempotencyKey, onSaved) {
                            saving = false
                            error = it
                        }
                    }) { Text(if (saving) "جارٍ التحقق..." else "إعادة المحاولة") }
                } else {
                    Button(enabled = !saving && eligibleLines.isNotEmpty(), onClick = {
                        val selected = eligibleLines.mapNotNull { line ->
                            val raw = quantities[line.id].orEmpty().trim()
                            if (raw.isBlank()) null else {
                                val quantity = raw.toBigDecimalOrNull()
                                if (quantity == null || quantity <= java.math.BigDecimal.ZERO ||
                                    quantity > java.math.BigDecimal("999999999999.999999") ||
                                    quantity.stripTrailingZeros().scale() > 6) {
                                    error = "أدخل كمية موجبة بحد أقصى 6 منازل عشرية."
                                    return@Button
                                }
                                val remaining = (line.quantityOrdered.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                                    .subtract(line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                                if (quantity > remaining) {
                                    error = "كمية الاستلام تتجاوز المتبقي للبند ${line.lineNumber}."
                                    return@Button
                                }
                                CommercialPurchaseReceiptLineInput(line.id, quantity.stripTrailingZeros().toPlainString())
                            }
                        }
                        if (selected.isEmpty()) {
                            error = "أدخل كمية مستلمة لبند واحد على الأقل."
                        } else if (order.receiveLocationId.isNullOrBlank()) {
                            error = "أمر الشراء لا يحدد موقع الاستلام."
                        } else {
                            if (idempotencyKey.isBlank()) {
                                idempotencyKey = getOrCreateCommercialPurchaseReceiptIdempotencyKey(context, order.id)
                            }
                            attempted = true
                            saving = true
                            error = ""
                            submitPurchaseReceipt(context, order, eligibleLines, quantities.toMap(), idempotencyKey, onSaved) {
                                saving = false
                                error = it
                            }
                        }
                    }) { Text(if (saving) "جارٍ الاستلام..." else "اعتماد الاستلام") }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving && !attempted, onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun submitPurchaseReceipt(
    context: Context,
    order: CommercialCloudPurchaseDocument,
    eligibleLines: List<CommercialCloudPurchaseLine>,
    values: Map<String, String>,
    idempotencyKey: String,
    onSaved: () -> Unit,
    onFailure: (String) -> Unit
) {
    val lines = eligibleLines.mapNotNull { line ->
        values[line.id].orEmpty().trim().takeIf { it.isNotEmpty() }?.let {
            CommercialPurchaseReceiptLineInput(line.id, it)
        }
    }
    PurchaseReceiptWorker.execute {
        val result = runCatching {
            receiveCommercialCloudPurchaseOrder(context, order.id, order.receiveLocationId.orEmpty(), lines, idempotencyKey)
        }
        PurchaseReceiptMain.post {
            result.onSuccess {
                clearCommercialPurchaseReceiptIdempotencyKey(context, order.id, idempotencyKey)
                onSaved()
            }.onFailure { onFailure(it.message ?: "تعذر تسجيل الاستلام.") }
        }
    }
}
