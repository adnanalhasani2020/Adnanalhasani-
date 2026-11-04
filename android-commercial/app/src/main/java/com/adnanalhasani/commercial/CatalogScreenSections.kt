package com.adnanalhasani.commercial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogCategorySection(categoryCodeState: MutableState<String>, parentCodeState: MutableState<String>, categoryNameArState: MutableState<String>, categoryNameEnState: MutableState<String>, categoryDescriptionArState: MutableState<String>, categoryDescriptionEnState: MutableState<String>, categoryTranslationsState: MutableState<String>, categories: List<CatalogCategory>, locale: String, busy: Boolean, onCreateCategory: () -> Unit, onLoadDirectories: () -> Unit) {
    var categoryCode by categoryCodeState
    var parentCode by parentCodeState
    var categoryNameAr by categoryNameArState
    var categoryNameEn by categoryNameEnState
    var categoryDescriptionAr by categoryDescriptionArState
    var categoryDescriptionEn by categoryDescriptionEnState
    var categoryTranslations by categoryTranslationsState
                    item { Text("إضافة مستوى تصنيف جديد — اترك التصنيف الأب فارغًا لإنشاء جذر.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(categoryCode,{categoryCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف")},singleLine=true) }
                    item { OutlinedTextField(parentCode,{parentCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف الأب (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(categoryNameAr,{categoryNameAr=it},Modifier.fillMaxWidth(),label={Text("الاسم بالعربية")},singleLine=true) }
                    item { OutlinedTextField(categoryNameEn,{categoryNameEn=it},Modifier.fillMaxWidth(),label={Text("Name in English")},singleLine=true) }
                    item { OutlinedTextField(categoryDescriptionAr,{categoryDescriptionAr=it},Modifier.fillMaxWidth(),label={Text("الوصف العربي")}) }
                    item { OutlinedTextField(categoryDescriptionEn,{categoryDescriptionEn=it},Modifier.fillMaxWidth(),label={Text("English description")}) }
                    item { OutlinedTextField(categoryTranslations,{categoryTranslations=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("ترجمات إضافية: locale|field|value")}) }
                    item { Button(onClick={onCreateCategory()},enabled=!busy && categoryCode.isNotBlank() && categoryNameAr.isNotBlank() && categoryNameEn.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ التصنيف") } }
                    item { OutlinedButton(onClick={onLoadDirectories()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحميل التصنيفات والوحدات") } }
                    items(categories,key={it.code}) { cat -> Column(verticalArrangement=Arrangement.spacedBy(2.dp)) { Text("${cat.code}  •  ${cat.localizedName.ifBlank { cat.nameAr }} / ${cat.nameEn}${if(cat.parentCode.isNotBlank()) "  ←  ${cat.parentCode}" else "  •  جذر"}",style=MaterialTheme.typography.bodySmall); val desc=cat.localizedDescription.ifBlank { if(locale.startsWith("en")) cat.descriptionEn else cat.descriptionAr }; if(desc.isNotBlank()) Text(desc,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
}

@Composable
internal fun CatalogUnitSection(unitShortCodeState: MutableState<String>, unitNameArState: MutableState<String>, unitNameEnState: MutableState<String>, unitDimensionState: MutableState<String>, unitBaseCodeState: MutableState<String>, unitFactorState: MutableState<String>, unitDecimalsState: MutableState<String>, unitTranslationsState: MutableState<String>, directoryUnits: List<DirectoryUnit>, busy: Boolean, onCreateUnit: () -> Unit, onLoadDirectories: () -> Unit) {
    var unitShortCode by unitShortCodeState
    var unitNameAr by unitNameArState
    var unitNameEn by unitNameEnState
    var unitDimension by unitDimensionState
    var unitBaseCode by unitBaseCodeState
    var unitFactor by unitFactorState
    var unitDecimals by unitDecimalsState
    var unitTranslations by unitTranslationsState
                    item { Text("إنشاء وحدة خاصة بالمؤسسة. رمز الوحدة سيُسبق تلقائيًا برمز المؤسسة لمنع تعارض الرموز عالميًا.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(unitShortCode,{unitShortCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("الرمز المختصر")},singleLine=true) }
                    item { OutlinedTextField(unitNameAr,{unitNameAr=it},Modifier.fillMaxWidth(),label={Text("اسم الوحدة بالعربية")},singleLine=true) }
                    item { OutlinedTextField(unitNameEn,{unitNameEn=it},Modifier.fillMaxWidth(),label={Text("Unit name in English")},singleLine=true) }
                    item { OutlinedTextField(unitDimension,{unitDimension=it.lowercase()},Modifier.fillMaxWidth(),label={Text("البعد (مثل count, mass, length, volume, area)")},singleLine=true) }
                    item {
                        val canonicalUnits=directoryUnits.filter { it.baseCode.isBlank() }
                        if(canonicalUnits.isEmpty()) OutlinedTextField(unitBaseCode,{unitBaseCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("رمز وحدة الأساس (حمّل الأدلة أولًا)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=canonicalUnits.firstOrNull{it.code==unitBaseCode}?.let{"${it.code} — ${it.localizedName.ifBlank { it.nameAr }}"} ?: unitBaseCode,
                                    onValueChange={},readOnly=true,label={Text("وحدة الأساس القياسية")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    canonicalUnits.forEach { unit -> DropdownMenuItem(text={Text("${unit.code} — ${unit.localizedName.ifBlank { unit.nameAr }} (${unit.dimension})")},onClick={unitBaseCode=unit.code;unitDimension=unit.dimension;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(unitFactor,{unitFactor=it},Modifier.fillMaxWidth(),label={Text("كم تساوي وحدة الأساس؟")},singleLine=true) }
                    item { OutlinedTextField(unitDecimals,{unitDecimals=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("عدد المنازل العشرية (0–6)")},singleLine=true) }
                    item { OutlinedTextField(unitTranslations,{unitTranslations=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("ترجمات إضافية: locale|field|value")}) }
                    item { Button(onClick={onCreateUnit()},enabled=!busy && unitShortCode.isNotBlank() && unitNameAr.isNotBlank() && unitNameEn.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ وحدة القياس") } }
                    item { OutlinedButton(onClick={onLoadDirectories()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحديث دليل الوحدات") } }
                    items(directoryUnits,key={it.code}) { u -> Text("${u.code} • ${u.nameAr} / ${u.nameEn} • ${u.dimension} • ${u.factor} ${u.baseCode}${if(u.owner.isNotBlank()) " • وحدة مؤسسة" else ""}",style=MaterialTheme.typography.bodySmall) }
                }
}

@Composable
internal fun CatalogTransferSection(transferSourceState: MutableState<String>, transferDestinationState: MutableState<String>, transferIdsState: MutableState<String>, pendingTransfers: List<PendingTransfer>, busy: Boolean, onTransferCatalog: () -> Unit, onLoadPendingTransfers: () -> Unit, onRespondToTransfer: (String, Boolean) -> Unit) {
    var transferSource by transferSourceState
    var transferDestination by transferDestinationState
    var transferIds by transferIdsState
                    item { Text("النقل متاح فقط إذا كنت مالكًا أو مديرًا في الحساب المصدر والوجهة. اترك قائمة UUID فارغة لنقل كل روابط الدليل.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(transferSource,{transferSource=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف الحساب المصدر UUID")},singleLine=true) }
                    item { OutlinedTextField(transferDestination,{transferDestination=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف الحساب الوجهة UUID")},singleLine=true) }
                    item { OutlinedTextField(transferIds,{transferIds=it},Modifier.fillMaxWidth(),label={Text("معرّفات الأصناف UUID مفصولة بفواصل (اختياري)")}) }
                    item { Button(onClick={onTransferCatalog()},enabled=!busy && transferSource.isNotBlank() && transferDestination.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("إرسال طلب نقل الدليل") } }
                    item { OutlinedButton(onClick={onLoadPendingTransfers()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحميل طلبات النقل الواردة لهذا الحساب") } }
                    items(pendingTransfers,key={it.id}) { transfer ->
                        Card(border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text("طلب نقل • ${transfer.itemCount} صنف",fontWeight=FontWeight.SemiBold)
                                Text("المصدر: ${transfer.source}",style=MaterialTheme.typography.bodySmall)
                                Text("رقم الطلب: ${transfer.id}",style=MaterialTheme.typography.bodySmall)
                                Text("تاريخ الطلب: ${transfer.createdAt}",style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={onRespondToTransfer(transfer.id,true)},enabled=!busy,modifier=Modifier.weight(1f)) { Text("قبول النقل") }
                                    OutlinedButton(onClick={onRespondToTransfer(transfer.id,false)},enabled=!busy,modifier=Modifier.weight(1f)) { Text("رفض") }
                                }
                            }
                        }
                    }
                }
}
