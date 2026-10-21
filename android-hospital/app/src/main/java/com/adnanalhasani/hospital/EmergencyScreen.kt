package com.adnanalhasani.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val erNavy = Color(0xFF263B4D)
private val erMuted = Color(0xFF788895)
private val erRed = Color(0xFFB93838)
private val erOrange = Color(0xFFC77720)
private val erGreen = Color(0xFF39816F)
private val erCanvas = Color(0xFFF3F6F8)
private val erBorder = Color(0xFFE1E8ED)
private val erTriageLevels = listOf("حرج", "عاجل", "مستقر")
private val erStatuses = listOf("قيد التقييم", "قيد العلاج", "تحويل", "خروج", "تنويم")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmergencyScreen(repository: HospitalRepository, onEdit: suspend (Long, String, String) -> Unit, onDelete: (Long) -> Unit) {
    val records by repository.allRecords().collectAsState(initial = emptyList())
    val cases = remember(records) {
        records.filter { it.category == "emergency" }.sortedWith(
            compareBy<HospitalRecord> {
                when (RecordEditHistory.visibleDetails(it.details).lineSequence()
                    .firstOrNull { line -> line.startsWith("درجة الفرز:") }
                    ?.substringAfter(": ")?.trim()) {
                    "حرج" -> 0
                    "عاجل" -> 1
                    "مستقر" -> 2
                    else -> 3
                }
            }.thenByDescending { it.createdAt }
        )
    }
    val scope = rememberCoroutineScope()
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val fields = listOf(
        "اسم المريض" to "الاسم الكامل أو هوية مؤقتة",
        "رقم الملف" to "رقم الملف إن توفر",
        "وقت الوصول" to "YYYY-MM-DD HH:mm",
        "الشكوى الرئيسية" to "سبب الحضور والأعراض الأساسية",
        "الأعراض والعلامات" to "بداية الأعراض ومدتها والعلامات الملحوظة",
        "ضغط الدم" to "مثال: 120/80",
        "النبض" to "نبضة/دقيقة",
        "معدل التنفس" to "نفس/دقيقة",
        "تشبع الأكسجين" to "SpO₂ %",
        "درجة الحرارة" to "°C",
        "مستوى الوعي" to "واعي / يستجيب للصوت / للألم / لا يستجيب",
        "الحساسية" to "أدوية أو مواد معروفة أو غير معروف",
        "الأمراض والأدوية" to "أمراض مزمنة وأدوية حالية",
        "الممارس المسؤول" to "اسم الممرض أو الطبيب",
        "الإجراء الأولي" to "ما تم عمله فعلياً",
        "ملاحظات التسليم" to "ملاحظات للفريق التالي"
    )
    fun initialValues() = fields.associate { it.first to "" } + ("وقت الوصول" to LocalDateTime.now().format(formatter))
    var values by remember { mutableStateOf(initialValues()) }
    var triage by remember { mutableStateOf("عاجل") }
    var triageExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("قيد التقييم") }
    var statusExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<HospitalRecord?>(null) }
    var editingRecord by remember { mutableStateOf<HospitalRecord?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDetails by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    val filtered = cases.filter { it.title.contains(search, true) || RecordEditHistory.visibleDetails(it.details).contains(search, true) }
    val parsedCases = cases.map { record ->
        record to RecordEditHistory.visibleDetails(record.details).lineSequence().mapNotNull { line ->
            val i = line.indexOf(": ")
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 2).trim()
        }.toMap()
    }
    val criticalCount = parsedCases.count { it.second["درجة الفرز"] == "حرج" && it.second["حالة الحالة"] !in listOf("خروج", "تحويل") }
    val urgentCount = parsedCases.count { it.second["درجة الفرز"] == "عاجل" && it.second["حالة الحالة"] !in listOf("خروج", "تحويل") }
    val activeCount = parsedCases.count { it.second["حالة الحالة"] in listOf("قيد التقييم", "قيد العلاج") }

    LazyColumn(Modifier.fillMaxSize().background(erCanvas), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalHospital, null, tint = erRed, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("إدارة قسم الطوارئ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = erNavy)
                            Text("استقبال الحالة • فرز أولي • متابعة • تسليم", color = erMuted, fontSize = 12.sp)
                        }
                    }
                    Text("للتوثيق والتنظيم فقط: لا تحسب الشاشة خطورة سريرية تلقائياً ولا تستبدل بروتوكول الفرز المعتمد أو تقييم الفريق الطبي.", color = erMuted, fontSize = 11.sp, lineHeight = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ErMetric("الإجمالي", cases.size.toString(), erNavy, Modifier.weight(1f))
                        ErMetric("حرجة", criticalCount.toString(), erRed, Modifier.weight(1f))
                        ErMetric("عاجلة", urgentCount.toString(), erOrange, Modifier.weight(1f))
                        ErMetric("نشطة", activeCount.toString(), erGreen, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("تسجيل حالة طوارئ", color = erNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("رقم الحالة يُنشأ تلقائياً. أدخل القياسات كما تم قياسها واترك غير المتوفر فارغاً.", color = erMuted, fontSize = 12.sp)
                    fields.forEach { (label, hint) ->
                        OutlinedTextField(
                            value = values[label].orEmpty(),
                            onValueChange = { values = values + (label to it); error = null; success = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) }, placeholder = { Text(hint, fontSize = 11.sp) },
                            singleLine = label !in listOf("الشكوى الرئيسية", "الأعراض والعلامات", "الأمراض والأدوية", "الإجراء الأولي", "ملاحظات التسليم"),
                            minLines = if (label in listOf("الشكوى الرئيسية", "الأعراض والعلامات", "الأمراض والأدوية", "الإجراء الأولي", "ملاحظات التسليم")) 2 else 1,
                            shape = RoundedCornerShape(13.dp)
                        )
                    }
                    ErDropdown("درجة الفرز", triage, erTriageLevels, triageExpanded, { triageExpanded = it }) { triage = it; triageExpanded = false }
                    ErDropdown("حالة الحالة", status, erStatuses, statusExpanded, { statusExpanded = it }) { status = it; statusExpanded = false }
                    Button(
                        onClick = {
                            val name = values["اسم المريض"].orEmpty().trim()
                            val complaint = values["الشكوى الرئيسية"].orEmpty().trim()
                            val arrival = values["وقت الوصول"].orEmpty().trim()
                            if (name.isBlank() || complaint.isBlank() || arrival.isBlank()) {
                                error = "أكمل اسم المريض والشكوى الرئيسية ووقت الوصول."
                                return@Button
                            }
                            if (!HospitalFieldValidation.isValidDateTime(arrival)) {
                                error = "وقت الوصول غير صحيح. استخدم YYYY-MM-DD HH:mm."
                                return@Button
                            }
                            val rules = listOf("النبض" to (1.0..300.0), "معدل التنفس" to (1.0..100.0), "تشبع الأكسجين" to (1.0..100.0), "درجة الحرارة" to (25.0..45.0))
                            val invalid = rules.firstOrNull { (label, range) ->
                                val raw = values[label].orEmpty().trim()
                                raw.isNotBlank() && (raw.toDoubleOrNull()?.let { it !in range } ?: true)
                            }
                            if (invalid != null) {
                                error = "تحقق من القيمة المدخلة في حقل " + invalid.first + "."
                                return@Button
                            }
                            val bp = values["ضغط الدم"].orEmpty().trim()
                            if (bp.isNotBlank() && !Regex("^\\d{2,3}/\\d{2,3}(\\s*mmHg)?$", RegexOption.IGNORE_CASE).matches(bp)) {
                                error = "صيغة ضغط الدم غير صحيحة. مثال: 120/80."
                                return@Button
                            }
                            val caseNumber = "ER-" + System.currentTimeMillis().toString().takeLast(8)
                            val detailPairs = listOf("رقم الحالة" to caseNumber) + fields.mapNotNull { (label, _) ->
                                values[label]?.trim()?.takeIf { it.isNotBlank() }?.let { label to it }
                            } + listOf("درجة الفرز" to triage, "حالة الحالة" to status)
                            val details = detailPairs.joinToString("\n") { it.first + ": " + it.second }
                            scope.launch {
                                saving = true
                                error = null
                                success = null
                                try {
                                    repository.saveRecord(HospitalRecord(category = "emergency", title = caseNumber, details = details))
                                    values = initialValues()
                                    triage = "عاجل"
                                    status = "قيد التقييم"
                                    success = "تم حفظ الحالة " + caseNumber + " محلياً."
                                } catch (_: Exception) {
                                    error = "تعذر حفظ الحالة. لم يتم تأكيد الحفظ؛ حاول مرة أخرى."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !saving,
                        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = erRed)
                    ) {
                        if (saving) CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (saving) "جارٍ حفظ الحالة..." else "حفظ حالة الطوارئ")
                    }
                    error?.let { Text(it, color = erRed, fontSize = 12.sp) }
                    success?.let { Text(it, color = erGreen, fontSize = 12.sp) }
                }
            }
        }
        item {
            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("بحث بالاسم أو رقم الحالة أو الملف أو الشكوى") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Text("سجل حالات الطوارئ", color = erNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        if (filtered.isEmpty()) item { Text("لا توجد حالات مطابقة للبحث.", color = erMuted, modifier = Modifier.padding(12.dp)) }
        items(filtered, key = { it.id }) { record ->
            val parsed = RecordEditHistory.visibleDetails(record.details).lineSequence().mapNotNull { line ->
                val i = line.indexOf(": ")
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 2).trim()
            }.toMap()
            val level = parsed["درجة الفرز"].orEmpty()
            val caseStatus = parsed["حالة الحالة"].orEmpty()
            val levelColor = when (level) { "حرج" -> erRed; "عاجل" -> erOrange; else -> erGreen }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, erBorder)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(record.title, color = erNavy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(parsed["اسم المريض"].orEmpty().ifBlank { "اسم غير مسجل" }, color = erMuted, fontSize = 13.sp)
                        }
                        Surface(color = levelColor.copy(alpha = .12f), shape = RoundedCornerShape(50)) {
                            Text(level.ifBlank { "غير محدد" }, color = levelColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Text(parsed["الشكوى الرئيسية"].orEmpty(), color = erNavy, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الوصول: " + parsed["وقت الوصول"].orEmpty(), color = erMuted, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(color = erCanvas, shape = RoundedCornerShape(8.dp)) {
                            Text(caseStatus.ifBlank { "غير محدد" }, color = erNavy, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 11.sp)
                        }
                    }
                    val vitals = listOf("ضغط الدم", "النبض", "معدل التنفس", "تشبع الأكسجين", "درجة الحرارة").mapNotNull { key ->
                        parsed[key]?.takeIf { it.isNotBlank() }?.let { key + ": " + it }
                    }
                    if (vitals.isNotEmpty()) Text(vitals.joinToString(" • "), color = erMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    if (parsed["ملاحظات التسليم"].orEmpty().isNotBlank()) Text("التسليم: " + parsed["ملاحظات التسليم"].orEmpty(), color = erMuted, fontSize = 12.sp)
                    Text("تحديث مسار الحالة", color = erMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("قيد العلاج", "تحويل", "تنويم", "خروج").forEach { nextStatus ->
                            TextButton(
                                onClick = {
                                    val lines = RecordEditHistory.visibleDetails(record.details).lineSequence().toMutableList()
                                    val statusIndex = lines.indexOfFirst { it.startsWith("حالة الحالة:") }
                                    if (statusIndex >= 0) lines[statusIndex] = "حالة الحالة: " + nextStatus
                                    else lines.add("حالة الحالة: " + nextStatus)
                                    val statusDetails = lines.joinToString("\n")
                                    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    val updatedDetails = RecordEditHistory.append(
                                        statusDetails,
                                        record.details,
                                        now,
                                        "تم تغيير حالة الطوارئ إلى $nextStatus"
                                    )
                                    scope.launch {
                                        runCatching { repository.updateEmergencyCase(record.id, updatedDetails, nextStatus) }
                                            .onFailure { error = "تعذر تحديث حالة الطوارئ." }
                                    }
                                },
                                enabled = caseStatus != nextStatus,
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                            ) { Text(nextStatus, fontSize = 10.sp, color = if (nextStatus == "خروج") erGreen else erNavy) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            editingRecord = record
                            editTitle = record.title
                            editDetails = RecordEditHistory.visibleDetails(record.details)
                            editError = null
                        }) {
                            Icon(Icons.Default.Edit, null, tint = erGreen)
                            Spacer(Modifier.width(4.dp))
                            Text("تعديل السجل", color = erGreen)
                        }
                        TextButton(onClick = { pendingDelete = record }) {
                            Icon(Icons.Default.DeleteOutline, null, tint = erRed)
                            Spacer(Modifier.width(4.dp))
                            Text("حذف", color = erRed)
                        }
                    }
                }
            }
        }
    }
    editingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { if (!isEditing) editingRecord = null },
            title = { Text("تعديل سجل الطوارئ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("رقم الحالة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDetails, onValueChange = { editDetails = it }, label = { Text("تفاصيل الحالة") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10)
                    Text("سيُضاف وقت التعديل تلقائياً إلى سجل التعديلات.", color = erMuted, fontSize = 11.sp)
                    editError?.let { Text(it, color = erRed, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isEditing && editTitle.isNotBlank(), onClick = {
                    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val savedDetails = RecordEditHistory.append(editDetails, record.details, now, "تم تعديل سجل الطوارئ")
                    scope.launch {
                        isEditing = true
                        runCatching { onEdit(record.id, editTitle.trim(), savedDetails) }
                            .onSuccess { editingRecord = null }
                            .onFailure { editError = "تعذر حفظ التعديل." }
                        isEditing = false
                    }
                }) { Text(if (isEditing) "جارٍ الحفظ..." else "حفظ التعديل") }
            },
            dismissButton = { TextButton(enabled = !isEditing, onClick = { editingRecord = null }) { Text("إلغاء") } }
        )
    }
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("تأكيد حذف حالة الطوارئ") },
            text = { Text("حذف الحالة " + record.title + " نهائياً من السجلات المحلية؟") },
            confirmButton = { TextButton(onClick = { onDelete(record.id); pendingDelete = null }) { Text("حذف", color = erRed) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErDropdown(label: String, selected: String, options: List<String>, expanded: Boolean, onExpanded: (Boolean) -> Unit, onSelect: (String) -> Unit) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpanded) {
        OutlinedTextField(value = selected, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(13.dp))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option) }) }
        }
    }
}

@Composable
private fun ErMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(color.copy(alpha = .08f), RoundedCornerShape(12.dp)).padding(8.dp)) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = erMuted, fontSize = 10.sp, lineHeight = 13.sp)
    }
}
