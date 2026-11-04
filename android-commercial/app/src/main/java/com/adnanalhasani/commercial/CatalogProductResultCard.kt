package com.adnanalhasani.commercial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Focused presentation owner for one global catalog product result. */
@Composable
internal fun CatalogProductResultCard(
    item: CatalogProduct,
    selected: Boolean,
    marketPriceHint: String,
    busy: Boolean,
    organizationId: String,
    requireCloudInventoryContext: Boolean,
    selectedBranchId: String,
    selectedWarehouseId: String,
    selectedActivityCode: String,
    onSelectionChange: (Boolean) -> Unit,
    onFeedback: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onReviewRuling: (String) -> Unit,
    onAddToInventory: (CatalogProduct, String, String, String, String, String) -> Unit,
    onEditProduct: () -> Unit
) {
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    if(item.imageUrl.startsWith("https://",true)) AsyncImage(model=item.imageUrl,contentDescription=item.name,modifier=Modifier.fillMaxWidth().heightIn(max=180.dp),contentScale=ContentScale.Fit)
                                    Row(verticalAlignment=Alignment.Top) {
                                        Checkbox(
                                            checked=selected,
                                            onCheckedChange=onSelectionChange,
                                            enabled=!busy
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name.ifBlank { item.catalogCode },fontWeight=FontWeight.SemiBold)
                                            Text("${item.category} • ${item.catalogCode}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                        }
                                        if(item.recommendationScore>0) AssistChip(onClick={},label={Text("مقترح +${item.recommendationScore}")})
                                    }
                                    if(item.description.isNotBlank()) Text(item.description,style=MaterialTheme.typography.bodySmall)
                                    val facts=listOf(
                                        item.brand.takeIf{it.isNotBlank()}?.let{"العلامة: $it"},
                                        item.manufacturer.takeIf{it.isNotBlank()}?.let{"المصنّع: $it"},
                                        item.countryOfManufacture.takeIf{it.isNotBlank()}?.let{"بلد الصناعة: $it"},
                                        item.modelNumber.takeIf{it.isNotBlank()}?.let{"رقم الطراز: $it"},
                                        item.partNumber.takeIf{it.isNotBlank()}?.let{"رقم القطعة: $it"},
                                        item.imageAlt.takeIf{it.isNotBlank()}?.let{"وصف الصورة: $it"},
                                        item.barcode.takeIf{it.isNotBlank()}?.let{"الباركود الأساسي: $it"},
                                        "وحدة الأساس/البيع: ${item.unitCode}",
                                        item.availableUnits.takeIf{it.isNotEmpty()}?.joinToString("، "){u->"${u.packageName.ifBlank{u.unitName.ifBlank{u.unitCode}}} × ${u.quantityInBase}${if(u.barcode.isNotBlank()) " (باركود ${u.barcode})" else ""}"}?.let{"العبوات: $it"},
                                        marketPriceHint.takeIf { it.isNotBlank() }?.let{"سعر استرشادي مجمّع: "+it},
                                        item.createdAt.takeIf{it.isNotBlank()}?.let{"تاريخ إدخال سجل الدليل: $it"},
                                        item.createdBy.takeIf{it.isNotBlank()}?.let{"معرّف مدخل سجل الدليل: $it"},
                                        item.imageUrl.takeIf{it.isNotBlank()}?.let{"رابط الصورة الخارجية: $it"},
                                        item.materialComposition.takeIf{it.isNotBlank()}?.let{"تركيب المواد: $it"},
                                        item.ingredients.takeIf{it.isNotBlank()}?.let{"المكونات: $it"},
                                        item.recipe.takeIf{it.isNotBlank()}?.let{"الوصفة: $it"},
                                        item.specifications.takeIf{it.isNotBlank()}?.let{"المواصفات: $it"},
                                        item.allergens.takeIf{it.isNotBlank()}?.let{"مسببات الحساسية: $it"},
                                        item.storageInstructions.takeIf{it.isNotBlank()}?.let{"التخزين: $it"},
                                        item.usageInstructions.takeIf{it.isNotBlank()}?.let{"الاستخدام: $it"},
                                        item.warnings.takeIf{it.isNotBlank()}?.let{"تحذيرات: $it"},
                                        item.shelfLifeDays?.let{"مدة الصلاحية بالأيام: $it"},
                                        if(item.requiresExpiryDate) "يتطلب تاريخ صلاحية" else null,
                                        if(item.isBatchTracked) "يتطلب تتبع رقم التشغيلة" else null,
                                        if(item.isSerialTracked) "يتطلب تتبع الرقم التسلسلي" else null,
                                        item.attributesSchema.takeIf{it.isNotBlank() && it!="{}"}?.let{"حقول إضافية: $it"},
                                        if(item.prescriptionOnly) "صنف يتطلب وصفة طبية — تحقق من التصنيف المحلي" else null
                                    ).filterNotNull()
                                    facts.forEach{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                    item.rulings.forEach { ruling ->
                                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                        Text("الحكم ${ruling.domain}: ${ruling.outcome} • الجهة: ${ruling.authority} • الحالة: ${ruling.confidence}",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                                        if(ruling.religion.isNotBlank()) Text("الدين/المذهب: ${ruling.religion}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.jurisdiction.isNotBlank()) Text("الاختصاص: ${ruling.jurisdiction}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.rationale.isNotBlank()) Text(ruling.rationale,style=MaterialTheme.typography.bodySmall)
                                        if(ruling.sourceTitle.isNotBlank()) Text("المصدر: ${ruling.sourceTitle}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.sourceUrl.isNotBlank()) Text("رابط المصدر: ${ruling.sourceUrl}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                        if(ruling.sourceUrl.startsWith("https://",true)) TextButton(onClick={
                                            onOpenSource(ruling.sourceUrl)
                                        }) { Text("فتح المصدر") }
                                        if(ruling.effectiveFrom.isNotBlank() || ruling.effectiveTo.isNotBlank()) Text("فترة السريان: ${ruling.effectiveFrom.ifBlank { "غير محدد" }} — ${ruling.effectiveTo.ifBlank { "مفتوح" }}",style=MaterialTheme.typography.labelSmall)
                                        if(ruling.reviewedAt.isNotBlank()) Text("تاريخ المراجعة: ${ruling.reviewedAt}",style=MaterialTheme.typography.labelSmall)
                                        if((ruling.confidence=="unverified" || ruling.confidence=="reported") && ruling.sourceTitle.isNotBlank() && ruling.sourceUrl.startsWith("https://",true)) {
                                            OutlinedButton(onClick={onReviewRuling(ruling.id)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تسجيل مراجعة المصدر (مالك/مدير)") }
                                        }
                                    }
                                    item.availableUnits.filter { it.imageUrl.startsWith("https://",true) }.forEach { unit ->
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            AsyncImage(model=unit.imageUrl,contentDescription=unit.packageName.ifBlank { unit.unitCode },modifier=Modifier.size(64.dp),contentScale=ContentScale.Crop)
                                            Column(Modifier.weight(1f)) {
                                                Text("صورة العبوة: ${unit.packageName.ifBlank { unit.unitCode }}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                                                Text(unit.imageUrl,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    Text("معرّف الصنف: ${item.id}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TextButton(onClick={
 val baseUnit=item.availableUnits.firstOrNull { it.role.equals("base",true) && it.quantityInBase.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal.ONE)==0 }
 val saleUnit=item.availableUnits.firstOrNull { it.isDefault } ?: item.availableUnits.firstOrNull { it.role.equals("sale",true) } ?: baseUnit
 if(!requireCloudInventoryContext) {
  onAddToInventory(item,selectedBranchId,selectedWarehouseId,selectedActivityCode,baseUnit?.unitCode.orEmpty(),saleUnit?.unitCode.orEmpty())
 } else if(selectedActivityCode.isBlank() || selectedBranchId.isBlank() || selectedWarehouseId.isBlank()) {
  onFeedback("اختر النشاط والفرع والمستودع أولًا قبل إنشاء الصنف بالمخزون.")
 } else if(baseUnit==null || saleUnit==null) {
  onFeedback("لا يمكن إنشاء الصنف: دليل الوحدات لا يحتوي وحدة أساس ووحدة بيع بمعامل تحويل موثوق.")
 } else onAddToInventory(item,selectedBranchId,selectedWarehouseId,selectedActivityCode,baseUnit.unitCode,saleUnit.unitCode)
}) { Icon(Icons.Default.Add,null); Text("إنشاء وربط بالمخزون") } }
                    if(item.ownerOrganizationId.isNotBlank() && item.ownerOrganizationId==organizationId.trim()) {
                        OutlinedButton(onClick={onEditProduct()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تعديل بيانات الصنف") }
                    }
                                }
                            }
}
