package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors

private val OpeningValuationWorker = Executors.newSingleThreadExecutor()
private val OpeningValuationMain = Handler(Looper.getMainLooper())

private class OpeningInventoryBatchDraft(initialQuantity: String = "") {
    var quantity by mutableStateOf(initialQuantity)
    var unitCostMinor by mutableStateOf("")
    var batchNumber by mutableStateOf("")
    var expiresOn by mutableStateOf("")
    var stockCondition by mutableStateOf("sellable")
}

@Composable
internal fun CommercialOpeningInventoryBatchValuationDialog(
    context: Context,
    offeringId: String,
    productName: String,
    unitName: String,
    currencyCode: String,
    uncoveredQuantity: BigDecimal,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val drafts = remember(offeringId) {
        mutableStateListOf(OpeningInventoryBatchDraft(uncoveredQuantity.stripTrailingZeros().toPlainString()))
    }
    val idempotencyKey = remember(offeringId) { UUID.randomUUID().toString() }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val enteredQuantity = drafts.fold(BigDecimal.ZERO) { total, row ->
        total.add(row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }
    val quantitiesMatch = enteredQuantity.compareTo(uncoveredQuantity) == 0
    val validationError = drafts.mapIndexedNotNull { index, row ->
        val quantity = row.quantity.toBigDecimalOrNull()
        val cost = row.unitCostMinor.toLongOrNull()
        when {
            quantity == null -> "أدخل كمية صحيحة للدفعة ${index + 1}."
            quantity <= BigDecimal.ZERO || quantity.stripTrailingZeros().scale() > 0 ||
                quantity > BigDecimal("999999999999") -> "كمية الدفعة ${index + 1} يجب أن تكون عدداً صحيحاً موجباً."
            cost == null || cost < 0L -> "أدخل تكلفة الوحدة بالوحدة الصغرى للدفعة ${index + 1}."
            row.expiresOn.isNotBlank() && runCatching { java.time.LocalDate.parse(row.expiresOn) }.isFailure ->
                "تاريخ صلاحية الدفعة ${index + 1} غير صحيح؛ استخدم YYYY-MM-DD."
            else -> null
        }
    }.firstOrNull()
    val canSubmit = quantitiesMatch && validationError == null

    fun submit() {
        if (saving) return
        if (!attempted && !canSubmit) {
            error = validationError ?: "يجب أن يساوي مجموع الدفعات الكمية غير المغطاة تماماً."
            return
        }
        saving = true
        attempted = true
        error = ""
        val payload = drafts.map {
            CommercialOpeningInventoryBatchInput(it.quantity, it.unitCostMinor, it.batchNumber, it.expiresOn, it.stockCondition)
        }
        OpeningValuationWorker.execute {
            val result = runCatching {
                recordCommercialOpeningInventoryBatches(context, offeringId, payload, idempotencyKey)
            }
            OpeningValuationMain.post {
                result.onSuccess { onSaved() }
                    .onFailure {
                        saving = false
                        error = it.message ?: "تعذر تسجيل تقييم الدفعات."
                    }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("تقييم الرصيد الافتتاحي · $productName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "هذا الرصيد موجود بالفعل لكنه بلا تكلفة موثقة. أدخل الدفعة أو الدفعات الفعلية وتكلفة الوحدة الصحيحة. لن تتغير الكمية الإجمالية ولن تُنشأ حركة مخزون جديدة.",
                    fontSize = 12.sp
                )
                Text(
                    "العملة: $currencyCode. أدخل التكلفة بالوحدة النقدية الصغرى، وليس بالقيمة العشرية المعروضة في الفاتورة.",
                    fontSize = 11.sp
                )
                Text(
                    "الكمية غير المغطاة بطبقات تكلفة: ${uncoveredQuantity.stripTrailingZeros().toPlainString()} $unitName",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    "إجمالي الكميات المدخلة: ${enteredQuantity.stripTrailingZeros().toPlainString()} $unitName",
                    fontSize = 11.sp
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    drafts.forEachIndexed { index, row ->
                        Card(colors = CardDefaults.cardColors()) {
                            Column(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("الدفعة ${index + 1}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = row.quantity,
                                    onValueChange = { if (!attempted) row.quantity = it.take(24) },
                                    label = { Text("كمية هذه الدفعة") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !attempted
                                )
                                OutlinedTextField(
                                    value = row.unitCostMinor,
                                    onValueChange = { if (!attempted) row.unitCostMinor = it.filter(Char::isDigit).take(20) },
                                    label = { Text("تكلفة الوحدة بالوحدة الصغرى · $currencyCode") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !attempted
                                )
                                OutlinedTextField(
                                    value = row.batchNumber,
                                    onValueChange = { if (!attempted) row.batchNumber = it.take(100) },
                                    label = { Text("رقم التشغيلة / الدفعة (اختياري)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !attempted
                                )
                                OutlinedTextField(
                                    value = row.expiresOn,
                                    onValueChange = { if (!attempted) row.expiresOn = it.take(10) },
                                    label = { Text("الصلاحية YYYY-MM-DD (اختياري)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    enabled = !attempted
                                )
                                Text("حالة الدفعة عند الجرد", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    FilterChip(
                                        selected = row.stockCondition == "sellable",
                                        onClick = { if (!attempted) row.stockCondition = "sellable" },
                                        enabled = !attempted,
                                        label = { Text("صالحة") }
                                    )
                                    FilterChip(
                                        selected = row.stockCondition == "quarantine",
                                        onClick = { if (!attempted) row.stockCondition = "quarantine" },
                                        enabled = !attempted,
                                        label = { Text("محجورة") }
                                    )
                                    FilterChip(
                                        selected = row.stockCondition == "damaged",
                                        onClick = { if (!attempted) row.stockCondition = "damaged" },
                                        enabled = !attempted,
                                        label = { Text("تالفة") }
                                    )
                                }
                                Text(
                                    "الدفعة المحجورة أو التالفة لا تدخل في الصرف. إذا كان تاريخ الصلاحية منتهياً، يصنفها الخادم منتهية تلقائياً.",
                                    fontSize = 10.sp
                                )
                                if (!attempted && drafts.size > 1) {
                                    TextButton(onClick = { drafts.remove(row) }) { Text("حذف هذه الدفعة") }
                                }
                            }
                        }
                    }
                }
                if (!attempted) {
                    TextButton(
                        enabled = drafts.size < 100,
                        onClick = { drafts.add(OpeningInventoryBatchDraft()) }
                    ) { Text("إضافة دفعة أخرى") }
                }
                if (!quantitiesMatch) {
                    Text("يجب أن يساوي مجموع الدفعات الكمية غير المغطاة تماماً.", fontSize = 11.sp)
                } else if (validationError != null) {
                    Text(validationError, fontSize = 11.sp)
                }
                if (error.isNotBlank()) Text(error, fontSize = 11.sp)
                if (attempted && !saving) {
                    Text("أُرسلت العملية. أعد المحاولة بالبيانات نفسها حتى لا يتكرر التسجيل.", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && (attempted || canSubmit),
                onClick = { submit() }
            ) {
                Text(if (saving) "جارٍ التسجيل..." else if (attempted) "إعادة المحاولة" else "تسجيل الدفعات")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
