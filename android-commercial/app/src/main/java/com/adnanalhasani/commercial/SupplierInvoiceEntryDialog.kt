package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.Executors

private val SupplierInvoiceWorker = Executors.newSingleThreadExecutor()
private val SupplierInvoiceMain = Handler(Looper.getMainLooper())

@Composable
internal fun SupplierInvoiceEntryDialog(
    context: Context,
    order: CommercialCloudPurchaseDocument,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var invoiceNumber by remember(order.id) { mutableStateOf("") }
    var tax by remember(order.id) { mutableStateOf("0") }
    var freight by remember(order.id) { mutableStateOf("0") }
    var quantities by remember(order.id) { mutableStateOf(order.lines.associate { it.id to "0" }) }
    var prices by remember(order.id) { mutableStateOf(order.lines.associate { it.id to it.unitCostMinor.toString() }) }
    var idempotencyKey by remember(order.id) { mutableStateOf(UUID.randomUUID().toString()) }
    var saving by remember(order.id) { mutableStateOf(false) }
    var error by remember(order.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("تسجيل فاتورة المورد · ${order.orderNumber}") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("المورد: ${order.supplierName} · العملة: ${order.currencyCode}")
                    OutlinedTextField(
                        value = invoiceNumber,
                        onValueChange = { invoiceNumber = it; idempotencyKey = UUID.randomUUID().toString() },
                        label = { Text("رقم فاتورة المورد") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !saving
                    )
                }
                items(order.lines, key = { it.id }) { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${line.lineNumber}. ${line.description}")
                        Text("المستلم: ${line.quantityReceived} · تكلفة الأمر: ${line.unitCostMinor}")
                        OutlinedTextField(
                            value = quantities[line.id].orEmpty(),
                            onValueChange = { quantities = quantities + (line.id to it); idempotencyKey = UUID.randomUUID().toString() },
                            label = { Text("الكمية المفوترة (0 لتجاوز البند)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !saving
                        )
                        OutlinedTextField(
                            value = prices[line.id].orEmpty(),
                            onValueChange = { prices = prices + (line.id to it); idempotencyKey = UUID.randomUUID().toString() },
                            label = { Text("سعر الوحدة بالوحدة الصغرى") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !saving
                        )
                    }
                }
                item {
                    OutlinedTextField(value = tax, onValueChange = { tax = it; idempotencyKey = UUID.randomUUID().toString() }, label = { Text("الضريبة (الوحدة الصغرى)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                    OutlinedTextField(value = freight, onValueChange = { freight = it; idempotencyKey = UUID.randomUUID().toString() }, label = { Text("الشحن (الوحدة الصغرى)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFB3261E))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val number = invoiceNumber.trim()
                val taxValue = tax.toLongOrNull()
                val freightValue = freight.toLongOrNull()
                val lineInputs = mutableListOf<CommercialSupplierInvoiceLineInput>()
                var lineError = ""
                order.lines.forEach { line ->
                    val raw = quantities[line.id].orEmpty().trim()
                    if (raw.isNotBlank()) {
                        val quantity = raw.toBigDecimalOrNull()
                        if (quantity == null || quantity < java.math.BigDecimal.ZERO) lineError = "كمية غير صالحة للبند ${line.lineNumber}."
                        else if (quantity > java.math.BigDecimal.ZERO) {
                            val price = prices[line.id].orEmpty().trim().toLongOrNull()
                            if (price == null || price < 0L) lineError = "سعر غير صالح للبند ${line.lineNumber}."
                            else lineInputs.add(CommercialSupplierInvoiceLineInput(line.id, raw, price))
                        }
                    }
                }
                val validation = when {
                    number.isBlank() -> "أدخل رقم فاتورة المورد."
                    taxValue == null || taxValue < 0L -> "الضريبة غير صالحة."
                    freightValue == null || freightValue < 0L -> "الشحن غير صالح."
                    lineInputs.isEmpty() -> "أدخل كمية موجبة لبند واحد على الأقل."
                    lineError.isNotBlank() -> lineError
                    else -> ""
                }
                if (validation.isNotBlank()) error = validation else {
                    saving = true
                    error = ""
                    val key = idempotencyKey
                    SupplierInvoiceWorker.execute {
                        val result = runCatching {
                            recordCommercialCloudSupplierInvoice(
                                context, order.id, number, order.currencyCode,
                                lineInputs, taxValue!!, freightValue!!, key
                            )
                        }
                        SupplierInvoiceMain.post {
                            result.onSuccess {
                                saving = false
                                onSaved()
                            }.onFailure {
                                saving = false
                                error = it.message ?: "تعذر حفظ فاتورة المورد."
                            }
                        }
                    }
                }
            }) { Text(if (saving) "جارٍ الحفظ..." else "حفظ الفاتورة") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("إلغاء") } }
    )
}
