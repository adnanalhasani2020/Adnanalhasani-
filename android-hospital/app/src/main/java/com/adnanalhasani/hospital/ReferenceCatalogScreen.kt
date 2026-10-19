package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class ReferenceCategory(val key: String, val title: String, val defaults: List<String>)

private val referenceCategories = listOf(
    ReferenceCategory("lab_tests", "الفحوصات المخبرية", listOf("صورة دم كاملة CBC", "سكر الدم", "الهيموغلوبين السكري HbA1c", "وظائف الكلى", "وظائف الكبد", "تحليل البول", "سرعة الترسيب ESR", "CRP", "دهون الدم", "هرمون الغدة الدرقية TSH")),
    ReferenceCategory("medications", "الأدوية", listOf("باراسيتامول", "إيبوبروفين", "أموكسيسيلين", "أوميبرازول", "ميتفورمين", "محلول ملحي 0.9%", "سيتريزين")),
    ReferenceCategory("diagnoses", "التشخيصات والأمراض", listOf("ارتفاع ضغط الدم", "داء السكري من النوع الثاني", "فقر الدم", "التهاب الجهاز التنفسي العلوي", "التهاب المسالك البولية", "الربو", "التهاب المعدة والأمعاء")),
    ReferenceCategory("specialties", "التخصصات الطبية", listOf("طب الأسرة", "الباطنية", "الجراحة العامة", "طب الأطفال", "النساء والولادة", "القلب", "العظام", "الأنف والأذن والحنجرة", "العيون", "الجلدية")),
    ReferenceCategory("departments", "الأقسام والأجنحة", listOf("الطوارئ", "العيادات الخارجية", "الباطنية", "الجراحة", "الأطفال", "النساء والولادة", "العناية المركزة", "المختبر", "الأشعة", "الصيدلية")),
    ReferenceCategory("blood_groups", "فصائل الدم", listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")),
    ReferenceCategory("payment_methods", "طرق الدفع", listOf("نقداً", "بطاقة", "تحويل بنكي", "تأمين")),
    ReferenceCategory("lab_status", "حالات الفحوصات", listOf("مطلوب", "قيد التنفيذ", "مكتمل", "ملغي")),
    ReferenceCategory("admission_status", "حالات التنويم", listOf("قيد التقييم", "منوّم", "خرج", "محول")),
    ReferenceCategory("vaccination_status", "حالات التطعيم", listOf("تم التطعيم", "موعد قادم", "تأجيل", "تعذر")),
    ReferenceCategory("imaging_types", "أنواع التصوير", listOf("أشعة سينية X-Ray", "موجات فوق صوتية", "أشعة مقطعية CT", "رنين مغناطيسي MRI"))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceCatalogScreen(repository: HospitalRepository) {
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(referenceCategories.first().key) }
    val category = referenceCategories.first { it.key == selectedCategory }
    val items by repository.referenceItems(selectedCategory).collectAsState(initial = emptyList())
    var editor by remember { mutableStateOf<ReferenceCatalogEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        referenceCategories.forEach { repository.seedReferenceDefaults(it.key, it.defaults) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("إدارة القوائم المرجعية", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        Text("تُحفظ الخيارات في قاعدة بيانات Room ويمكن تعديلها دون تغيير الكود. تعطيل العنصر يحافظ على السجلات التاريخية.", fontSize = 12.sp, color = Color(0xFF788895))
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = category.title,
                onValueChange = {},
                readOnly = true,
                label = { Text("نوع القائمة") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                referenceCategories.forEach { option ->
                    DropdownMenuItem(text = { Text(option.title) }, onClick = {
                        selectedCategory = option.key
                        expanded = false
                        message = null
                    })
                }
            }
        }
        Button(onClick = {
            editor = null
            name = ""
            description = ""
            showEditor = true
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("إضافة عنصر")
        }
        message?.let { Text(it, color = Color(0xFF548F88), fontSize = 12.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(items, key = { it.id }) { item ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF263B4D))
                            if (item.description.isNotBlank()) Text(item.description, fontSize = 12.sp, color = Color(0xFF788895))
                            Text(if (item.active) "نشط" else "معطّل", fontSize = 11.sp, color = if (item.active) Color(0xFF548F88) else Color.Gray)
                        }
                        IconButton(onClick = {
                            editor = item
                            name = item.name
                            description = item.description
                            showEditor = true
                        }) { Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = Color(0xFF548F88)) }
                        if (item.active) {
                            IconButton(onClick = {
                                scope.launch {
                                    repository.deactivateReference(item.id)
                                    message = "تم تعطيل العنصر مع الحفاظ على السجلات القديمة."
                                }
                            }) { Icon(Icons.Default.Block, contentDescription = "تعطيل", tint = Color(0xFFC97979)) }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editor == null) "إضافة عنصر مرجعي" else "تعديل عنصر مرجعي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("وصف اختياري") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val current = editor
                        val success = if (current == null) repository.addReference(selectedCategory, name, description)
                            else repository.updateReference(current.id, name, description, current.active)
                        message = if (success) "تم حفظ العنصر في قاعدة البيانات." else "تعذر الحفظ: تحقق من الاسم وعدم تكراره."
                        if (success) showEditor = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("إلغاء") } }
        )
    }
}
