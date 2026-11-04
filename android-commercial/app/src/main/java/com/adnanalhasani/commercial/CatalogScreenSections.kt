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
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
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
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
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
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogProductFormSection(state: CatalogProductFormState, categories: List<CatalogCategory>, directoryUnits: List<DirectoryUnit>, busy: Boolean, onCreateProduct: () -> Unit) {
    var nameAr by state::nameAr
    var nameEn by state::nameEn
    var descriptionAr by state::descriptionAr
    var descriptionEn by state::descriptionEn
    var extraTranslations by state::extraTranslations
    var productCategory by state::productCategory
    var itemType by state::itemType
    var unitCode by state::unitCode
    var barcode by state::barcode
    var brand by state::brand
    var manufacturer by state::manufacturer
    var origin by state::origin
    var model by state::model
    var partNumber by state::partNumber
    var imageUrl by state::imageUrl
    var imageAlt by state::imageAlt
    var ingredients by state::ingredients
    var materialComposition by state::materialComposition
    var recipe by state::recipe
    var recipeIngredientsText by state::recipeIngredientsText
    var recipeYieldQty by state::recipeYieldQty
    var recipeYieldUnit by state::recipeYieldUnit
    var recipePrepMinutes by state::recipePrepMinutes
    var recipeCookMinutes by state::recipeCookMinutes
    var recipeSource by state::recipeSource
    var recipeAllergenNotes by state::recipeAllergenNotes
    var specifications by state::specifications
    var customAttributesJson by state::customAttributesJson
    var allergens by state::allergens
    var storage by state::storage
    var usage by state::usage
    var warnings by state::warnings
    var shelfLife by state::shelfLife
    var expiryRequired by state::expiryRequired
    var batchTracked by state::batchTracked
    var serialTracked by state::serialTracked
    var prescriptionOnly by state::prescriptionOnly
    var extraUnitCode by state::extraUnitCode
    var extraUnitQty by state::extraUnitQty
    var extraUnitBarcode by state::extraUnitBarcode
    var extraUnitSku by state::extraUnitSku
    var extraUnitPackage by state::extraUnitPackage
    var extraUnitNetQuantity by state::extraUnitNetQuantity
    var extraUnitNetUnitCode by state::extraUnitNetUnitCode
    var extraUnitImageUrl by state::extraUnitImageUrl
    var extraUnitsText by state::extraUnitsText
    var currencyCode by state::currencyCode
    var sectors by state::sectors
    var levels by state::levels
    var regionCountry by state::regionCountry
    var regionCode by state::regionCode
    var regionCity by state::regionCity
    var regionExcluded by state::regionExcluded
    var extraRegionRules by state::extraRegionRules
    var rulingDomain by state::rulingDomain
    var authority by state::authority
    var religion by state::religion
    var jurisdiction by state::jurisdiction
    var rulingOutcome by state::rulingOutcome
    var rulingRationale by state::rulingRationale
    var rulingSourceTitle by state::rulingSourceTitle
    var rulingSourceUrl by state::rulingSourceUrl
    var rulingConfidence by state::rulingConfidence
    var extraRulingsText by state::extraRulingsText
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    item { Text("بيانات الصنف الأساسية",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(nameAr,{nameAr=it},Modifier.fillMaxWidth(),label={Text("اسم الصنف بالعربية *")},singleLine=true) }
                    item { OutlinedTextField(nameEn,{nameEn=it},Modifier.fillMaxWidth(),label={Text("Product name in English *")},singleLine=true) }
                    item {
                        if(categories.isEmpty()) OutlinedTextField(productCategory,{productCategory=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف * (حدّث الأدلة أولًا للاختيار)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=categories.firstOrNull{it.code==productCategory}?.let{"${it.code} — ${it.nameAr}"} ?: productCategory,
                                    onValueChange={},readOnly=true,label={Text("التصنيف *")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    categories.forEach { cat -> DropdownMenuItem(text={Text("${cat.code} — ${cat.localizedName.ifBlank { cat.nameAr }}")},onClick={productCategory=cat.code;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(itemType,{itemType=it},Modifier.fillMaxWidth(),label={Text("نوع العنصر: product/service/digital/material/asset")},singleLine=true) }
                    item { OutlinedTextField(descriptionAr,{descriptionAr=it},Modifier.fillMaxWidth(),label={Text("الوصف العربي")}) }
                    item { OutlinedTextField(descriptionEn,{descriptionEn=it},Modifier.fillMaxWidth(),label={Text("English description")}) }
                    item { OutlinedTextField(extraTranslations,{extraTranslations=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("ترجمات إضافية لكل لغة — سطر لكل ترجمة: locale|field|value")},placeholder={Text("fr|name|Nom du produit\nfr|description|Description du produit")}) }
                    item { Text("الباركود والوحدات والعبوات",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item {
                        if(directoryUnits.isEmpty()) OutlinedTextField(unitCode,{unitCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة الأساس * (حدّث الأدلة أولًا للاختيار)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=directoryUnits.firstOrNull{it.code==unitCode}?.let{"${it.code} — ${it.nameAr}"} ?: unitCode,
                                    onValueChange={},readOnly=true,label={Text("وحدة الأساس *")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    directoryUnits.forEach { unit -> DropdownMenuItem(text={Text("${unit.code} — ${unit.nameAr} (${unit.dimension})")},onClick={unitCode=unit.code;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(barcode,{barcode=it.filter(Char::isDigit).take(14)},Modifier.fillMaxWidth(),label={Text("GTIN / الباركود الأساسي (8–14 رقمًا)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitCode,{extraUnitCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة عبوة إضافية (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitQty,{extraUnitQty=it},Modifier.fillMaxWidth(),label={Text("كم وحدة أساس داخل العبوة")},singleLine=true) }
                    item { OutlinedTextField(extraUnitPackage,{extraUnitPackage=it},Modifier.fillMaxWidth(),label={Text("اسم العبوة (كرتون/علبة/شريط)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitBarcode,{extraUnitBarcode=it},Modifier.fillMaxWidth(),label={Text("باركود العبوة الإضافية")},singleLine=true) }
                    item { OutlinedTextField(extraUnitSku,{extraUnitSku=it},Modifier.fillMaxWidth(),label={Text("SKU للعبوة الإضافية")},singleLine=true) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(extraUnitNetQuantity,{extraUnitNetQuantity=it},Modifier.weight(1f),label={Text("الكمية الصافية")},singleLine=true); OutlinedTextField(extraUnitNetUnitCode,{extraUnitNetUnitCode=it.uppercase()},Modifier.weight(1f),label={Text("وحدة الصافي")},singleLine=true) } }
                    item { OutlinedTextField(extraUnitImageUrl,{extraUnitImageUrl=it},Modifier.fillMaxWidth(),label={Text("رابط صورة العبوة HTTPS (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitsText,{extraUnitsText=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("عبوات إضافية متعددة — سطر: unit_code|role|qty_in_base|barcode|sku|package_name|net_quantity|net_unit_code|image_url")}) }
                    item { Text("العلامة والمصنع والمواصفات",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(brand,{brand=it},Modifier.fillMaxWidth(),label={Text("العلامة التجارية")},singleLine=true) }
                    item { OutlinedTextField(manufacturer,{manufacturer=it},Modifier.fillMaxWidth(),label={Text("الشركة المصنعة")},singleLine=true) }
                    item { OutlinedTextField(origin,{origin=it},Modifier.fillMaxWidth(),label={Text("بلد الصناعة")},singleLine=true) }
                    item { OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("رقم الطراز")},singleLine=true) }
                    item { OutlinedTextField(partNumber,{partNumber=it},Modifier.fillMaxWidth(),label={Text("رقم القطعة")},singleLine=true) }
                    item { OutlinedTextField(imageUrl,{imageUrl=it},Modifier.fillMaxWidth(),label={Text("رابط صورة HTTPS خارجي")},singleLine=true) }
                    item { OutlinedTextField(imageAlt,{imageAlt=it},Modifier.fillMaxWidth(),label={Text("وصف الصورة")},singleLine=true) }
                    item { OutlinedTextField(ingredients,{ingredients=it},Modifier.fillMaxWidth(),label={Text("المكونات مفصولة بفواصل")}) }
                    item { OutlinedTextField(materialComposition,{materialComposition=it},Modifier.fillMaxWidth(),label={Text("تركيب المواد مفصول بفواصل")}) }
                    item { OutlinedTextField(recipe,{recipe=it},Modifier.fillMaxWidth(),label={Text("الوصفة / طريقة التحضير")}) }
                    item { OutlinedTextField(recipeIngredientsText,{recipeIngredientsText=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("مكونات الوصفة — سطر: name|quantity|unit_code|note")}) }
                    item { OutlinedTextField(recipeYieldQty,{recipeYieldQty=it},Modifier.fillMaxWidth(),label={Text("كمية الناتج من الوصفة")},singleLine=true) }
                    item { OutlinedTextField(recipeYieldUnit,{recipeYieldUnit=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة الناتج")},singleLine=true) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(recipePrepMinutes,{recipePrepMinutes=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("دقائق التحضير")},singleLine=true); OutlinedTextField(recipeCookMinutes,{recipeCookMinutes=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("دقائق الطبخ")},singleLine=true) } }
                    item { OutlinedTextField(recipeAllergenNotes,{recipeAllergenNotes=it},Modifier.fillMaxWidth(),label={Text("ملاحظات الحساسية للوصفة")}) }
                    item { OutlinedTextField(recipeSource,{recipeSource=it},Modifier.fillMaxWidth(),label={Text("مصدر الوصفة / المرجع")},singleLine=true) }
                    item { OutlinedTextField(specifications,{specifications=it},Modifier.fillMaxWidth(),label={Text("المواصفات الإضافية")}) }
                    item { OutlinedTextField(customAttributesJson,{customAttributesJson=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("حقول مخصصة إضافية (JSON object)")},placeholder={Text("{\"color\":\"black\",\"model\":\"X1\"}")}) }
                    item { OutlinedTextField(allergens,{allergens=it},Modifier.fillMaxWidth(),label={Text("مسببات الحساسية مفصولة بفواصل")}) }
                    item { OutlinedTextField(storage,{storage=it},Modifier.fillMaxWidth(),label={Text("تعليمات التخزين")}) }
                    item { OutlinedTextField(usage,{usage=it},Modifier.fillMaxWidth(),label={Text("تعليمات الاستخدام")}) }
                    item { OutlinedTextField(warnings,{warnings=it},Modifier.fillMaxWidth(),label={Text("التحذيرات")}) }
                    item { OutlinedTextField(shelfLife,{shelfLife=it.filter(Char::isDigit).take(6)},Modifier.fillMaxWidth(),label={Text("مدة الصلاحية بالأيام")},singleLine=true) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(expiryRequired,{expiryRequired=it}); Text("يتطلب تاريخ صلاحية") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(batchTracked,{batchTracked=it}); Text("تتبع رقم التشغيلة") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(serialTracked,{serialTracked=it}); Text("تتبع الرقم التسلسلي") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(prescriptionOnly,{prescriptionOnly=it}); Text("يتطلب وصفة طبية (بعد التحقق)") } }
                    item { OutlinedTextField(currencyCode,{currencyCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("رمز العملة لحساب المؤسسة")},singleLine=true) }
                    item { Text("القطاعات ومستويات النشاط والمناطق",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(sectors,{sectors=it},Modifier.fillMaxWidth(),label={Text("القطاعات مفصولة بفواصل (فارغ = عام)")}) }
                    item { OutlinedTextField(levels,{levels=it},Modifier.fillMaxWidth(),label={Text("مستويات النشاط مفصولة بفواصل (فارغ = عام)")}) }
                    item { OutlinedTextField(regionCountry,{regionCountry=it.uppercase()},Modifier.fillMaxWidth(),label={Text("دولة الإظهار (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(regionCode,{regionCode=it},Modifier.fillMaxWidth(),label={Text("رمز المنطقة / المحافظة")},singleLine=true) }
                    item { OutlinedTextField(regionCity,{regionCity=it},Modifier.fillMaxWidth(),label={Text("المدينة")},singleLine=true) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(regionExcluded,{regionExcluded=it}); Text("استثناء هذه المنطقة بدل تضمينها") } }
                    item { OutlinedTextField(extraRegionRules,{extraRegionRules=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("مناطق إضافية متعددة — سطر: country|region|city|include أو exclude")}) }
                    item { Text("الحكم الديني / الطبي / التنظيمي (اختياري، لا يُعد فتوى أو تشخيصًا)",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(rulingDomain,{rulingDomain=it},Modifier.fillMaxWidth(),label={Text("النطاق: religious / medical / regulatory / dietary")},singleLine=true) }
                    item { OutlinedTextField(authority,{authority=it},Modifier.fillMaxWidth(),label={Text("رمز الجهة أو المرجع (مطلوب لتسجيل الحكم)")},singleLine=true) }
                    item { OutlinedTextField(religion,{religion=it},Modifier.fillMaxWidth(),label={Text("رمز الدين عند الحكم الديني")},singleLine=true) }
                    item { OutlinedTextField(jurisdiction,{jurisdiction=it},Modifier.fillMaxWidth(),label={Text("الدولة / الاختصاص القضائي")},singleLine=true) }
                    item { OutlinedTextField(rulingOutcome,{rulingOutcome=it},Modifier.fillMaxWidth(),label={Text("النتيجة: permitted/prohibited/conditional/unknown/...")},singleLine=true) }
                    item { OutlinedTextField(rulingRationale,{rulingRationale=it},Modifier.fillMaxWidth(),label={Text("التعليل / الشروط")}) }
                    item { OutlinedTextField(rulingSourceTitle,{rulingSourceTitle=it},Modifier.fillMaxWidth(),label={Text("عنوان المصدر")},singleLine=true) }
                    item { OutlinedTextField(rulingSourceUrl,{rulingSourceUrl=it},Modifier.fillMaxWidth(),label={Text("رابط المصدر HTTPS")},singleLine=true) }
                    item { OutlinedTextField(rulingConfidence,{rulingConfidence=it},Modifier.fillMaxWidth(),label={Text("الحالة الأولية: unverified أو reported؛ المراجعة لها إجراء منفصل")},singleLine=true) }
                    item { OutlinedTextField(extraRulingsText,{extraRulingsText=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("أحكام إضافية متعددة — domain|authority|religion|jurisdiction|outcome|source_title|source_url|confidence|rationale")}) }
                    item { Button(onClick={onCreateProduct()},enabled=!busy && nameAr.isNotBlank() && nameEn.isNotBlank() && productCategory.isNotBlank() && unitCode.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ الصنف وكل تفاصيله") } }
                    item { Text("يُحفظ سجل الصنف والوحدات والاستهداف والأحكام في معاملة واحدة. لا يُعتمد أي حكم طبي أو ديني تلقائيًا؛ ارفع الثقة بعد مراجعة مصدر موثوق.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
}
