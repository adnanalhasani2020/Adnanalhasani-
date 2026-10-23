package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private fun jsonArrayLines(raw: String): String = runCatching {
    val values = JSONArray(raw)
    (0 until values.length()).joinToString("\n") { values.get(it).toString() }
}.getOrDefault("")

private fun nonBlankLines(raw: String): JSONArray = JSONArray().apply {
    raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { put(it) }
}

@Composable
internal fun CatalogProductEditDialog(
    item: CatalogProduct,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit
) {
    var nameAr by remember(item.id) { mutableStateOf(item.nameAr) }
    var nameEn by remember(item.id) { mutableStateOf(item.nameEn) }
    var descriptionAr by remember(item.id) { mutableStateOf(item.descriptionAr) }
    var descriptionEn by remember(item.id) { mutableStateOf(item.descriptionEn) }
    var brand by remember(item.id) { mutableStateOf(item.brand) }
    var manufacturer by remember(item.id) { mutableStateOf(item.manufacturer) }
    var country by remember(item.id) { mutableStateOf(item.countryOfManufacture) }
    var model by remember(item.id) { mutableStateOf(item.modelNumber) }
    var part by remember(item.id) { mutableStateOf(item.partNumber) }
    var barcode by remember(item.id) { mutableStateOf(item.barcodeGtin) }
    var imageUrl by remember(item.id) { mutableStateOf(item.imageUrl) }
    var imageAlt by remember(item.id) { mutableStateOf(item.imageAlt) }
    var material by remember(item.id) { mutableStateOf(jsonArrayLines(item.rawMaterialComposition)) }
    var ingredients by remember(item.id) { mutableStateOf(jsonArrayLines(item.rawIngredients)) }
    var specificationsJson by remember(item.id) { mutableStateOf(item.rawSpecifications.ifBlank { "{}" }) }
    var allergens by remember(item.id) { mutableStateOf(jsonArrayLines(item.rawAllergens)) }
    var storage by remember(item.id) { mutableStateOf(item.storageInstructions) }
    var usage by remember(item.id) { mutableStateOf(item.usageInstructions) }
    var warnings by remember(item.id) { mutableStateOf(item.warnings) }
    var shelfLife by remember(item.id) { mutableStateOf(item.shelfLifeDays?.toString().orEmpty()) }
    var attributesJson by remember(item.id) { mutableStateOf(item.rawAttributesSchema.ifBlank { "{}" }) }
    var requiresExpiry by remember(item.id) { mutableStateOf(item.requiresExpiryDate) }
    var batchTracked by remember(item.id) { mutableStateOf(item.isBatchTracked) }
    var serialTracked by remember(item.id) { mutableStateOf(item.isSerialTracked) }
    var prescriptionOnly by remember(item.id) { mutableStateOf(item.prescriptionOnly) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل بيانات الدليل: ${item.catalogCode}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "هذا النموذج يعدّل تفاصيل الصنف المملوك لهذه المؤسسة فقط. الوحدات والعبوات، وصفة التصنيع التفصيلية، قواعد النشاط والمنطقة، والأحكام محفوظة دون تغيير.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { OutlinedTextField(nameAr,{nameAr=it;error=null},Modifier.fillMaxWidth(),label={Text("الاسم العربي *")},singleLine=true) }
                    item { OutlinedTextField(nameEn,{nameEn=it;error=null},Modifier.fillMaxWidth(),label={Text("الاسم الإنجليزي *")},singleLine=true) }
                    item { OutlinedTextField(descriptionAr,{descriptionAr=it},Modifier.fillMaxWidth(),label={Text("الوصف العربي")},minLines=2) }
                    item { OutlinedTextField(descriptionEn,{descriptionEn=it},Modifier.fillMaxWidth(),label={Text("الوصف الإنجليزي")},minLines=2) }
                    item { OutlinedTextField(brand,{brand=it},Modifier.fillMaxWidth(),label={Text("العلامة التجارية")},singleLine=true) }
                    item { OutlinedTextField(manufacturer,{manufacturer=it},Modifier.fillMaxWidth(),label={Text("الشركة المصنعة")},singleLine=true) }
                    item { OutlinedTextField(country,{country=it},Modifier.fillMaxWidth(),label={Text("بلد الصناعة")},singleLine=true) }
                    item { OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("رقم الطراز")},singleLine=true) }
                    item { OutlinedTextField(part,{part=it},Modifier.fillMaxWidth(),label={Text("رقم القطعة")},singleLine=true) }
                    item { OutlinedTextField(barcode,{barcode=it.filter(Char::isDigit).take(14)},Modifier.fillMaxWidth(),label={Text("باركود GTIN (8–14 رقمًا)")},singleLine=true) }
                    item { OutlinedTextField(imageUrl,{imageUrl=it},Modifier.fillMaxWidth(),label={Text("رابط الصورة HTTPS فقط")},singleLine=true) }
                    item { OutlinedTextField(imageAlt,{imageAlt=it},Modifier.fillMaxWidth(),label={Text("وصف الصورة")}) }
                    item { OutlinedTextField(material,{material=it},Modifier.fillMaxWidth().heightIn(min=72.dp),label={Text("تركيب المواد — عنصر في كل سطر")}) }
                    item { OutlinedTextField(ingredients,{ingredients=it},Modifier.fillMaxWidth().heightIn(min=72.dp),label={Text("المكونات — عنصر في كل سطر")}) }
                    item { OutlinedTextField(specificationsJson,{specificationsJson=it;error=null},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("المواصفات (JSON object)")}) }
                    item { OutlinedTextField(allergens,{allergens=it},Modifier.fillMaxWidth().heightIn(min=72.dp),label={Text("مسببات الحساسية — عنصر في كل سطر")}) }
                    item { OutlinedTextField(storage,{storage=it},Modifier.fillMaxWidth(),label={Text("تعليمات التخزين")}) }
                    item { OutlinedTextField(usage,{usage=it},Modifier.fillMaxWidth(),label={Text("تعليمات الاستخدام")}) }
                    item { OutlinedTextField(warnings,{warnings=it},Modifier.fillMaxWidth(),label={Text("التحذيرات")}) }
                    item { OutlinedTextField(shelfLife,{shelfLife=it.filter(Char::isDigit);error=null},Modifier.fillMaxWidth(),label={Text("مدة الصلاحية بالأيام (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(attributesJson,{attributesJson=it;error=null},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("الحقول المخصصة (JSON object)")}) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(requiresExpiry,{requiresExpiry=it}); Text("يتطلب تاريخ صلاحية") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(batchTracked,{batchTracked=it}); Text("تتبع رقم التشغيلة") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(serialTracked,{serialTracked=it}); Text("تتبع الرقم التسلسلي") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(prescriptionOnly,{prescriptionOnly=it}); Text("يتطلب وصفة طبية") } }
                    if(error!=null) item { Text(error!!,color=MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if(nameAr.isBlank() || nameEn.isBlank()) { error="الاسمان العربي والإنجليزي مطلوبان."; return@Button }
                val specs=runCatching { JSONObject(specificationsJson) }.getOrElse { error="المواصفات يجب أن تكون JSON object صالحًا."; return@Button }
                val attributes=runCatching { JSONObject(attributesJson) }.getOrElse { error="الحقول المخصصة يجب أن تكون JSON object صالحًا."; return@Button }
                val shelf=shelfLife.toIntOrNull()
                if(shelfLife.isNotBlank() && (shelf==null || shelf<0)) { error="مدة الصلاحية يجب أن تكون عددًا صحيحًا غير سالب."; return@Button }
                val payload=JSONObject()
                    .put("name_ar",nameAr.trim()).put("name_en",nameEn.trim())
                    .put("description_ar",descriptionAr.trim().ifBlank { JSONObject.NULL })
                    .put("description_en",descriptionEn.trim().ifBlank { JSONObject.NULL })
                    .put("brand",brand.trim().ifBlank { JSONObject.NULL })
                    .put("manufacturer_name",manufacturer.trim().ifBlank { JSONObject.NULL })
                    .put("country_of_manufacture",country.trim().ifBlank { JSONObject.NULL })
                    .put("model_number",model.trim().ifBlank { JSONObject.NULL })
                    .put("part_number",part.trim().ifBlank { JSONObject.NULL })
                    .put("barcode_gtin",barcode.trim().ifBlank { JSONObject.NULL })
                    .put("image_url",imageUrl.trim().ifBlank { JSONObject.NULL })
                    .put("image_alt",imageAlt.trim().ifBlank { JSONObject.NULL })
                    .put("material_composition",nonBlankLines(material))
                    .put("ingredients",nonBlankLines(ingredients))
                    .put("specifications",specs)
                    .put("allergens",nonBlankLines(allergens))
                    .put("storage_instructions",storage.trim().ifBlank { JSONObject.NULL })
                    .put("usage_instructions",usage.trim().ifBlank { JSONObject.NULL })
                    .put("warnings",warnings.trim().ifBlank { JSONObject.NULL })
                    .put("shelf_life_days",shelf ?: JSONObject.NULL)
                    .put("requires_expiry_date",requiresExpiry)
                    .put("is_batch_tracked",batchTracked)
                    .put("is_serial_tracked",serialTracked)
                    .put("is_prescription_only",prescriptionOnly)
                    .put("attributes_schema",attributes)
                onSave(payload)
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(if(busy) "جارٍ الحفظ…" else "حفظ التعديلات") }
        },
        dismissButton = { OutlinedButton(onClick=onDismiss,enabled=!busy) { Text("إلغاء") } }
    )
}
