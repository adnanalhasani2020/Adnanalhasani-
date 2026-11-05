package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogInvoiceItemCreationDialog(
    state: CatalogInvoiceTransferState,
    categories: List<CatalogCategory>,
    directoryUnits: List<DirectoryUnit>,
    localUnitCodes: Set<String>,
    organizationId: String,
    mappingLines: List<InvoicePurchaseUnmappedLine>,
    selectedLineId: String,
    busy: Boolean,
    onCreate: () -> Unit
) {
    if (!state.showItemCreation) return
    val baseOptions = directoryUnits.filter {
        (it.owner.isBlank() || it.owner == organizationId) &&
            it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes
    }
    val saleOptions = directoryUnits.filter {
        (it.owner.isBlank() || it.owner == organizationId) &&
            (it.code == state.newBaseUnitCode || it.baseCode == state.newBaseUnitCode)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) state.showItemCreation = false },
        title = { Text("إنشاء صنف وربطه بأمر الشراء") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("أدخل بيانات الصنف المعتمدة. لا يُستنتج التصنيف أو الاسم الإنجليزي من وصف الفاتورة.", style = MaterialTheme.typography.bodySmall)
                if (!COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY) {
                    Text("هذه العملية غير متاحة سحابيًا حتى نشر ترحيل إنشاء الصنف وربطه بأمر الشراء. لن يتم إرسال أي طلب.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = state.newNameAr, onValueChange = { state.newNameAr = it.take(180) }, label = { Text("اسم الصنف بالعربية *") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.newNameEn, onValueChange = { state.newNameEn = it.take(180) }, label = { Text("اسم الصنف بالإنجليزية *") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { state.newCategoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("التصنيف: " + (categories.firstOrNull { it.code == state.newCategoryCode }?.nameAr ?: "اختر تصنيف الدليل"))
                    }
                    DropdownMenu(expanded = state.newCategoryMenuExpanded, onDismissRequest = { state.newCategoryMenuExpanded = false }) {
                        categories.forEach { category ->
                            DropdownMenuItem(text = { Text(category.nameAr + " • " + category.code) }, onClick = {
                                state.newCategoryCode = category.code
                                state.newCategoryMenuExpanded = false
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { state.newBaseUnitMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("وحدة الأساس: " + (baseOptions.firstOrNull { it.code == state.newBaseUnitCode }?.let { it.code + " • " + it.nameAr } ?: "اختر وحدة الأساس"))
                    }
                    DropdownMenu(expanded = state.newBaseUnitMenuExpanded, onDismissRequest = { state.newBaseUnitMenuExpanded = false }) {
                        baseOptions.forEach { unit ->
                            DropdownMenuItem(text = { Text(unit.code + " • " + unit.nameAr) }, onClick = {
                                state.newBaseUnitCode = unit.code
                                state.newSaleUnitCode = unit.code
                                state.newBaseUnitMenuExpanded = false
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { state.newSaleUnitMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("وحدة الفاتورة/البيع: " + (saleOptions.firstOrNull { it.code == state.newSaleUnitCode }?.let { it.code + " • " + it.nameAr } ?: "اختر وحدة متوافقة"))
                    }
                    DropdownMenu(expanded = state.newSaleUnitMenuExpanded, onDismissRequest = { state.newSaleUnitMenuExpanded = false }) {
                        saleOptions.forEach { unit ->
                            DropdownMenuItem(text = { Text(unit.code + " • " + unit.nameAr + " • " + unit.factor) }, onClick = {
                                state.newSaleUnitCode = unit.code
                                state.newSaleUnitMenuExpanded = false
                            })
                        }
                    }
                }
                Text("السعر سيبقى صفرًا بحالة غير مسعّر، والرصيد الافتتاحي صفر. إنشاء الصنف وربطه لا يستلم البضاعة؛ كما يتطلب صلاحية المالك أو المسؤول.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ملاحظة المخزون: المستودع يُحفظ في إعداد الصنف، لكن سجل الرصيد والاستلام الحاليين ما يزالان على مستوى الفرع؛ لا ينشأ رصيد مستقل للمستودع في هذه الخطوة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val selectedLine = mappingLines.firstOrNull { it.id == selectedLineId }
                if (selectedLine?.unitCode?.isNotBlank() == true) Text("وحدة بند الفاتورة: " + selectedLine.unitCode + " — يجب اختيارها كوحدة البيع نفسها.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = !busy && COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY &&
                    state.newNameAr.isNotBlank() && state.newNameEn.isNotBlank() &&
                    state.newCategoryCode.isNotBlank() && state.newBaseUnitCode.isNotBlank() &&
                    state.newSaleUnitCode.isNotBlank() && state.invoiceMappingWarehouseId.isNotBlank()
            ) { Text(if (busy) "جارٍ الإنشاء..." else "إنشاء وربط الصنف") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) state.showItemCreation = false }) { Text("إلغاء") } }
    )
}
