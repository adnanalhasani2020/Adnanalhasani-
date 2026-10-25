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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

private val PurchaseLineLinkWorker = Executors.newSingleThreadExecutor()
private val PurchaseLineLinkMain = Handler(Looper.getMainLooper())

@Composable
internal fun PurchaseLineOfferingLinkDialog(
    context: Context,
    order: CommercialCloudPurchaseDocument,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val missingLines = order.lines.filter { line ->
        val ordered = line.quantityOrdered.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val received = line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        received < ordered && line.offeringId.isNullOrBlank()
    }
    val selected = remember(order.id) { mutableStateMapOf<String, String>() }
    var options by remember { mutableStateOf<CommercialPurchaseOrderOptions?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val expandedMenus = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(order.id) {
        PurchaseLineLinkWorker.execute {
            val result = runCatching { loadCommercialCloudPurchaseOrderOptions(context) }
            PurchaseLineLinkMain.post {
                result.onSuccess { options = it }.onFailure { error = it.message ?: "تعذر تحميل عناصر المخزون." }
                loading = false
            }
        }
    }

    val available = options
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("ربط بنود المخزون · ${order.orderNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("لا يُسمح باستلام بند قديم غير مربوط. اختر عنصر المخزون الصحيح لكل بند متبقٍ. إذا كان الأمر معتمدًا، سيعود إلى حالة بانتظار الاعتماد بعد الربط.")
                if (loading) {
                    Text("جارٍ تحميل عناصر المخزون...")
                } else if (available == null) {
                    Text(error.ifBlank { "تعذر تحميل عناصر المخزون." })
                } else if (missingLines.isEmpty()) {
                    Text("لا توجد بنود متبقية تحتاج إلى ربط.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(missingLines, key = { it.id }) { line ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${line.lineNumber}. ${line.description}")
                                Text("المطلوب: ${line.quantityOrdered} · المستلم: ${line.quantityReceived.ifBlank { "0" }}")
                                TextButton(enabled = !saving && !attempted, onClick = { expandedMenus[line.id] = true }) {
                                    Text("عنصر المخزون: ${available.offerings.firstOrNull { it.id == selected[line.id] }?.name ?: "اختر العنصر"}")
                                }
                                DropdownMenu(
                                    expanded = expandedMenus[line.id] == true,
                                    onDismissRequest = { expandedMenus[line.id] = false }
                                ) {
                                    available.offerings.filter { it.currencyCode == order.currencyCode }.forEach { offering ->
                                        DropdownMenuItem(text = { Text(offering.name) }, onClick = {
                                            selected[line.id] = offering.id
                                            expandedMenus[line.id] = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                if (error.isNotBlank() && !loading) Text(error)
                if (attempted && !saving) Text("أعد المحاولة بنفس الاختيارات أو أغلق النافذة وافتحها بعد تحديث الأمر؛ الربط لا يضيف مخزونًا بذاته.")
            }
        },
        confirmButton = {
            Button(enabled = !loading && !saving && available != null && missingLines.isNotEmpty() &&
                missingLines.all { selected[it.id].orEmpty().isNotBlank() }, onClick = {
                if (!attempted) {
                    val optionsNow = available ?: return@Button
                    val valid = missingLines.all { line ->
                        optionsNow.offerings.any { it.id == selected[line.id] && it.currencyCode == order.currencyCode }
                    }
                    if (!valid) {
                        error = "اختر عنصر مخزون فعالًا وبعملة أمر الشراء لكل بند."
                        return@Button
                    }
                    attempted = true
                }
                saving = true
                error = ""
                val mappings = missingLines.map { it.id to selected[it.id].orEmpty() }
                PurchaseLineLinkWorker.execute {
                    val result = runCatching {
                        mappings.forEach { (lineId, offeringId) ->
                            linkCommercialCloudPurchaseOrderItemOffering(context, order.id, lineId, offeringId)
                        }
                    }
                    PurchaseLineLinkMain.post {
                        result.onSuccess { onSaved() }.onFailure {
                            saving = false
                            error = it.message ?: "تعذر ربط جميع البنود. يمكن إعادة المحاولة بأمان."
                        }
                    }
                }
            }) { Text(if (saving) "جارٍ الربط..." else if (attempted) "إعادة المحاولة" else "حفظ الربط") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("إغلاق") } }
    )
}
