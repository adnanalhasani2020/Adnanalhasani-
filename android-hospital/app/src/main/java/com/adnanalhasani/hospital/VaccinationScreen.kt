package com.adnanalhasani.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

private val vaccinationStatuses = listOf("تم التطعيم", "موعد قادم", "تأجيل", "تعذر")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaccinationScreen(repository: HospitalRepository, onDelete: (Long) -> Unit) {
    val today = LocalDate.now().toString()
    val records by repository.vaccinationRecords().collectAsState(initial = emptyList())
    val upcoming by repository.upcomingVaccinations(today).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val fields = listOf(
        "اسم الطفل" to "الاسم الرباعي",
        "رقم ملف الطفل" to "رقم الملف الصحي (اختياري)",
        "تاريخ الميلاد" to "YYYY-MM-DD (اختياري)",
        "اسم اللقاح" to "الاسم كما في السجل الرسمي",
        "رقم الجرعة" to "جرعة الولادة / الأولى / الثانية / معززة",
        "تاريخ التطعيم" to "YYYY-MM-DD",
        "موعد الجرعة التالية" to "YYYY-MM-DD (اختياري)",
        "رقم التشغيلة" to "رقم دفعة اللقاح (اختياري)",
        "مكان التطعيم" to "موضع إعطاء اللقاح (اختياري)",
        "الممارس الصحي" to "اسم الممارس الصحي (اختياري)",
        "ملاحظات" to "ملاحظات (اختياري)"
    )
    var values by remember { mutableStateOf(fields.associate { it.first to "" }) }
    var status by remember { mutableStateOf("تم التطعيم") }
    var statusExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val filtered = records.filter { search.isBlank() || it.childName.contains(search, true) || it.childFileNumber.contains(search, true) || it.vaccineName.contains(search, true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChildCare, contentDescription = null, tint = Color(0xFF548F88), modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("سجل تحصين الأطفال", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                    }
                    Text("أدخل الجرعات كما وثّقها الممارس الصحي، بالتاريخ YYYY-MM-DD.", color = Color(0xFF788895), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryTile("إجمالي الجرعات", records.size.toString(), Modifier.weight(1f))
                        SummaryTile("مواعيد قادمة", upcoming.size.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("تسجيل جرعة أو موعد", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                    fields.forEach { (label, hint) ->
                        OutlinedTextField(
                            value = values[label].orEmpty(),
                            onValueChange = { values = values + (label to it); message = null; errorMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) },
                            placeholder = { Text(hint, fontSize = 12.sp) },
                            singleLine = label != "ملاحظات",
                            minLines = if (label == "ملاحظات") 2 else 1,
                            shape = RoundedCornerShape(13.dp)
                        )
                    }
                    ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = it }) {
                        OutlinedTextField(
                            value = status, onValueChange = {}, readOnly = true,
                            label = { Text("حالة الجرعة") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(13.dp)
                        )
                        ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                            vaccinationStatuses.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { status = option; statusExpanded = false })
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val required = listOf("اسم الطفل", "اسم اللقاح", "رقم الجرعة", "تاريخ التطعيم")
                            val missing = required.firstOrNull { values[it].isNullOrBlank() }
                            if (missing != null) {
                                errorMessage = "أكمل الحقل المطلوب: " + missing
                                message = null
                                return@Button
                            }
                            val dateFields = listOf("تاريخ الميلاد", "تاريخ التطعيم", "موعد الجرعة التالية")
                            val invalidDate = dateFields.firstOrNull { key ->
                                val raw = values[key].orEmpty().trim()
                                raw.isNotBlank() && runCatching { LocalDate.parse(raw) }.isFailure
                            }
                            if (invalidDate != null) {
                                errorMessage = "التاريخ في حقل " + invalidDate + " غير صحيح. استخدم YYYY-MM-DD."
                                message = null
                                return@Button
                            }
                            val givenDate = values["تاريخ التطعيم"].orEmpty().trim()
                            val nextDate = values["موعد الجرعة التالية"].orEmpty().trim()
                            if (nextDate.isNotBlank() && nextDate < givenDate) {
                                errorMessage = "موعد الجرعة التالية لا يمكن أن يسبق تاريخ التطعيم."
                                message = null
                                return@Button
                            }
                            val details = (fields.mapNotNull { (label, _) ->
                                values[label]?.trim()?.takeIf { it.isNotBlank() }?.let { label + ": " + it }
                            } + ("حالة الجرعة: " + status)).joinToString("\n")
                            scope.launch {
                                saving = true
                                message = null
                                errorMessage = null
                                try {
                                    repository.saveRecord(HospitalRecord(category = "vaccination", title = values["اسم الطفل"].orEmpty().trim(), details = details))
                                    values = fields.associate { it.first to "" }
                                    status = "تم التطعيم"
                                    message = "تم حفظ السجل محلياً بنجاح."
                                } catch (_: Exception) {
                                    errorMessage = "تعذر الحفظ. تحقق من البيانات ثم أعد المحاولة."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !saving, shape = RoundedCornerShape(14.dp)
                    ) {
                        if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (saving) "جارٍ الحفظ..." else "حفظ سجل التطعيم")
                    }
                    message?.let { Text(it, color = Color(0xFF548F88), fontSize = 12.sp) }
                    errorMessage?.let { Text(it, color = Color(0xFFC97979), fontSize = 12.sp) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null, tint = Color(0xFF548F88))
                        Spacer(Modifier.width(8.dp))
                        Text("الجرعات ذات الموعد القادم", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF263B4D))
                    }
                    Text("قائمة مبنية على التواريخ المدخلة، وليست توصية طبية أو جدولاً وطنياً.", fontSize = 11.sp, color = Color(0xFF788895))
                    if (upcoming.isEmpty()) Text("لا توجد مواعيد مستقبلية مسجلة.", color = Color(0xFF788895))
                }
            }
        }
        items(upcoming, key = { "upcoming-" + it.id }) { record ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.childName, fontWeight = FontWeight.SemiBold, color = Color(0xFF263B4D))
                    Text(record.vaccineName + " • الجرعة " + record.doseNumber, color = Color(0xFF293B49), fontSize = 13.sp)
                    Text("الموعد: " + record.nextDoseDate, color = Color(0xFF548F88), fontSize = 12.sp)
                    if (record.childFileNumber.isNotBlank()) Text("رقم الملف: " + record.childFileNumber, color = Color(0xFF788895), fontSize = 11.sp)
                }
            }
        }
        item {
            Text("سجل الجرعات المحفوظة", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF263B4D))
            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("بحث باسم الطفل أو رقم الملف أو اللقاح") }, singleLine = true, shape = RoundedCornerShape(13.dp))
        }
        if (filtered.isEmpty()) item { Text("لا توجد سجلات مطابقة للبحث.", color = Color(0xFF788895), modifier = Modifier.padding(8.dp)) }
        items(filtered, key = { it.id }) { record ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(record.childName, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                            Text(record.vaccineName + " • " + record.doseNumber, color = Color(0xFF293B49), fontSize = 13.sp)
                        }
                        TextButton(onClick = { onDelete(record.sourceRecordId) }) { Icon(Icons.Default.Delete, contentDescription = "حذف السجل", tint = Color(0xFFC97979)) }
                    }
                    Text("تاريخ الجرعة: " + record.administeredDate + " • الحالة: " + record.doseStatus, fontSize = 12.sp, color = Color(0xFF788895))
                    if (record.nextDoseDate.isNotBlank()) Text("الجرعة التالية: " + record.nextDoseDate, fontSize = 12.sp, color = Color(0xFF548F88))
                    if (record.childFileNumber.isNotBlank()) Text("رقم الملف: " + record.childFileNumber, fontSize = 12.sp, color = Color(0xFF788895))
                    if (record.batchNumber.isNotBlank()) Text("التشغيلة: " + record.batchNumber, fontSize = 12.sp, color = Color(0xFF788895))
                    if (record.notes.isNotBlank()) Text("ملاحظات: " + record.notes, fontSize = 12.sp, color = Color(0xFF788895))
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F1EE)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
            Text(label, fontSize = 11.sp, color = Color(0xFF548F88))
        }
    }
}
