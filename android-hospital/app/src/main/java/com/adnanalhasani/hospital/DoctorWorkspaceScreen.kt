package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private fun doctorScreenField(record: HospitalRecord, label: String): String =
    record.details.lineSequence().firstOrNull { it.startsWith("$label: ") }?.substringAfter(": ")?.trim().orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorWorkspaceScreen(repository: HospitalRepository) {
    val scope = rememberCoroutineScope()
    val patients by repository.patients().collectAsState(initial = emptyList())
    val doctors by repository.doctors().collectAsState(initial = emptyList())
    val visits by repository.clinicalVisits().collectAsState(initial = emptyList())
    val allRecords by repository.allRecords().collectAsState(initial = emptyList())
    val labOrders by repository.labOrders().collectAsState(initial = emptyList())
    val radiologyOrders by repository.radiologyOrders().collectAsState(initial = emptyList())
    val prescriptionOrders by repository.prescriptionOrders().collectAsState(initial = emptyList())

    var patient by remember { mutableStateOf("") }
    var doctor by remember { mutableStateOf("") }
    var selectedPatientId by remember { mutableStateOf<Long?>(null) }
    var selectedDoctorId by remember { mutableStateOf<Long?>(null) }
    var visitAt by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())) }
    var complaint by remember { mutableStateOf("") }
    var diagnosis by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingVisit by remember { mutableStateOf<ClinicalVisitEntity?>(null) }
    var chartPatientId by remember { mutableStateOf<Long?>(null) }
    var chartPatientName by remember { mutableStateOf("") }
    var chartPatientExpanded by remember { mutableStateOf(false) }
    var queueDoctorId by remember { mutableStateOf<Long?>(null) }
    var queueDoctorName by remember { mutableStateOf("") }
    var queueDoctorExpanded by remember { mutableStateOf(false) }
    var activeVisit by remember { mutableStateOf<ClinicalVisitEntity?>(null) }
    var dialogType by remember { mutableStateOf("details") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogExtra by remember { mutableStateOf("") }
    var dialogSaving by remember { mutableStateOf(false) }

    fun clearForm() {
        editingVisit = null
        patient = ""
        doctor = ""
        selectedPatientId = null
        selectedDoctorId = null
        visitAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        complaint = ""
        diagnosis = ""
        notes = ""
    }

    fun visitDetails(): String = listOf(
        "اسم المريض: $patient",
        "اسم الطبيب: $doctor",
        "تاريخ الزيارة: $visitAt",
        "الشكوى الرئيسية: $complaint",
        "التشخيص: $diagnosis",
        "ملاحظات: $notes"
    ).joinToString("\n")

    val selectedChartPatient = patients.firstOrNull { it.id == chartPatientId }
    val patientVisits = visits.filter { visit ->
        if (chartPatientId != null) (visit.patientId == chartPatientId || (visit.patientId == null && visit.patientName.equals(chartPatientName, ignoreCase = true)))
        else chartPatientName.isNotBlank() && visit.patientName.equals(chartPatientName, ignoreCase = true)
    }
    val relatedRecords = allRecords.filter { record ->
        record.category != "encounters" &&
            chartPatientName.isNotBlank() &&
            (record.title.contains(chartPatientName, ignoreCase = true) ||
                record.details.contains(chartPatientName, ignoreCase = true))
    }.sortedByDescending { it.createdAt }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("شاشة الطبيب", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        Text(
            "ملف طبي مترابط: تسجيل الزيارة، مراجعة التاريخ الطبي، وتعديل الزيارة مع حفظ أثر التعديل.",
            fontSize = 12.sp, color = Color(0xFF788895)
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("قائمة انتظار العيادة", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF263B4D))
                        Text("اختر الطبيب لعرض المرضى الذين أحالهم الاستقبال إليه مباشرة.", fontSize = 12.sp, color = Color(0xFF788895))
                        ExposedDropdownMenuBox(expanded = queueDoctorExpanded, onExpandedChange = { queueDoctorExpanded = !queueDoctorExpanded }) {
                            OutlinedTextField(queueDoctorName, {}, readOnly = true, label = { Text("الطبيب المختص") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = queueDoctorExpanded, onDismissRequest = { queueDoctorExpanded = false }) {
                                doctors.forEach { option ->
                                    DropdownMenuItem(text = { Text("${option.fullName} • ${option.specialty}") }, onClick = {
                                        queueDoctorId = option.id; queueDoctorName = option.fullName; queueDoctorExpanded = false
                                    })
                                }
                            }
                        }
                        val waitingVisits = visits.filter { it.doctorId == queueDoctorId && queueDoctorId != null }.sortedByDescending { it.createdAt }
                        if (queueDoctorId == null) Text("اختر الطبيب لعرض قائمة المرضى المحالين.", fontSize = 12.sp, color = Color(0xFF788895))
                        else if (waitingVisits.isEmpty()) Text("لا توجد إحالات لهذا الطبيب حتى الآن.", fontSize = 12.sp, color = Color(0xFF788895))
                        else waitingVisits.forEach { visit ->
                            val p = patients.firstOrNull { it.id == visit.patientId }
                            OutlinedButton(onClick = {
                                activeVisit = visit; dialogType = "details"; dialogValue = ""; dialogExtra = ""
                                chartPatientId = visit.patientId; chartPatientName = visit.patientName
                            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(visit.patientName, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                                    Text("رقم الملف: ${p?.fileNumber ?: "غير معروف"} • ${visit.visitAt}", fontSize = 11.sp, color = Color(0xFF788895))
                                    Text("الشكوى: ${visit.chiefComplaint}", fontSize = 12.sp, color = Color(0xFF356D69))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (editingVisit == null) "تسجيل زيارة جديدة" else "تعديل الزيارة الطبية", fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                        if (patients.isEmpty() || doctors.isEmpty()) {
                            Text(
                                "يجب إنشاء سجل المريض والطبيب أولاً. لا تُحفظ الزيارة إلا بعد اختيار سجلين موجودين.",
                                color = Color(0xFF9A6B28), fontSize = 12.sp
                            )
                        }
                        var patientExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = patientExpanded, onExpandedChange = { patientExpanded = !patientExpanded }) {
                            OutlinedTextField(
                                value = patient, onValueChange = {}, readOnly = true,
                                label = { Text("اختر المريض *") },
                                modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
                            )
                            ExposedDropdownMenu(expanded = patientExpanded, onDismissRequest = { patientExpanded = false }) {
                                patients.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.fullName} • ${option.fileNumber}") },
                                        onClick = {
                                            patient = option.fullName
                                            selectedPatientId = option.id
                                            chartPatientId = option.id
                                            chartPatientName = option.fullName
                                            patientExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        var doctorExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = doctorExpanded, onExpandedChange = { doctorExpanded = !doctorExpanded }) {
                            OutlinedTextField(
                                value = doctor, onValueChange = {}, readOnly = true,
                                label = { Text("اختر الطبيب *") },
                                modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
                            )
                            ExposedDropdownMenu(expanded = doctorExpanded, onDismissRequest = { doctorExpanded = false }) {
                                doctors.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.fullName} • ${option.employeeNumber}") },
                                        onClick = { doctor = option.fullName; selectedDoctorId = option.id; doctorExpanded = false }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(value = visitAt, onValueChange = { visitAt = it }, label = { Text("تاريخ ووقت الزيارة *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = complaint, onValueChange = { complaint = it }, label = { Text("الشكوى الرئيسية *") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(value = diagnosis, onValueChange = { diagnosis = it }, label = { Text("التشخيص") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات وخطة المتابعة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (selectedPatientId == null || selectedDoctorId == null || patient.isBlank() || doctor.isBlank() || complaint.isBlank() || visitAt.isBlank()) {
                                        message = "اختر مريضًا وطبيبًا مسجلين وأدخل تاريخ الزيارة والشكوى الرئيسية."
                                    } else if (!HospitalFieldValidation.isValidDateTime(visitAt.trim())) {
                                        message = "تاريخ ووقت الزيارة غير صحيح. استخدم الصيغة YYYY-MM-DD HH:mm وتأكد من صلاحية التاريخ والوقت."
                                    } else {
                                        scope.launch {
                                            saving = true
                                            try {
                                                val currentEdit = editingVisit
                                                if (currentEdit == null) {
                                                    repository.saveClinicalVisit(
                                                        HospitalRecord(category = "encounters", title = patient, details = visitDetails()),
                                                        selectedPatientId!!, selectedDoctorId!!
                                                    )
                                                    message = "تم حفظ الزيارة وربطها بمعرّفي المريض والطبيب."
                                                } else {
                                                    repository.updateClinicalVisitRecord(currentEdit.sourceRecordId, patient, visitDetails(), selectedPatientId!!, selectedDoctorId!!)
                                                    message = "تم تعديل الزيارة وتسجيل القيم السابقة والجديدة في سجل التدقيق."
                                                }
                                                chartPatientId = selectedPatientId
                                                chartPatientName = patient
                                                clearForm()
                                            } catch (e: Exception) {
                                                message = "تعذر حفظ الزيارة. لم تُعرض رسالة نجاح؛ تحقق من البيانات وحاول مجددًا."
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !saving
                            ) {
                                Icon(if (editingVisit == null) Icons.Default.Save else Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (saving) "جارٍ الحفظ..." else if (editingVisit == null) "حفظ الزيارة" else "حفظ التعديلات")
                            }
                            if (editingVisit != null) {
                                OutlinedButton(onClick = { clearForm(); message = "تم إلغاء التعديل." }, enabled = !saving) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                    Text("إلغاء")
                                }
                            }
                        }
                        message?.let {
                            Text(it, color = if (it.startsWith("تم")) Color(0xFF548F88) else Color(0xFFC97979), fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("الملف الطبي للمريض", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF263B4D))
                        ExposedDropdownMenuBox(
                            expanded = chartPatientExpanded,
                            onExpandedChange = { chartPatientExpanded = !chartPatientExpanded }
                        ) {
                            OutlinedTextField(
                                value = chartPatientName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("اختر المريض لعرض تاريخه الطبي") },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(expanded = chartPatientExpanded, onDismissRequest = { chartPatientExpanded = false }) {
                                patients.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("${option.fullName} • ${option.fileNumber}") },
                                        onClick = {
                                            chartPatientId = option.id
                                            chartPatientName = option.fullName
                                            chartPatientExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        selectedChartPatient?.let { p ->
                            Text("رقم الملف: ${p.fileNumber}", fontSize = 13.sp, color = Color(0xFF788895))
                            if (p.phone.isNotBlank()) Text("الهاتف: ${p.phone}", fontSize = 13.sp)
                            if (p.age.isNotBlank()) Text("العمر: ${p.age}", fontSize = 13.sp)
                            if (p.notes.isNotBlank()) Text("ملاحظات الملف: ${p.notes}", fontSize = 13.sp)
                        }
                        Text("الزيارات المرتبطة: ${patientVisits.size}", fontWeight = FontWeight.SemiBold, color = Color(0xFF263B4D))
                        if (chartPatientName.isBlank()) {
                            Text("اختر مريضًا لعرض الزيارات والسجلات المرتبطة به.", color = Color(0xFF788895), fontSize = 12.sp)
                        } else if (patientVisits.isEmpty()) {
                            Text("لا توجد زيارات مسجلة لهذا المريض حتى الآن.", color = Color(0xFF788895), fontSize = 12.sp)
                        }
                    }
                }
            }

            if (chartPatientName.isNotBlank()) {
                items(patientVisits, key = { "visit-${it.id}" }) { visit ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(visit.patientName.ifBlank { "مريض غير محدد" }, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                            Text("رقم الزيارة: ${visit.id} • الطبيب: ${visit.doctorName.ifBlank { "غير محدد" }} • ${visit.visitAt}", color = Color(0xFF788895), fontSize = 11.sp)
                            if (visit.chiefComplaint.isNotBlank()) Text("الشكوى: ${visit.chiefComplaint}", fontSize = 13.sp)
                            if (visit.diagnosis.isNotBlank()) Text("التشخيص: ${visit.diagnosis}", fontSize = 13.sp)
                            if (visit.notes.isNotBlank()) Text("ملاحظات: ${visit.notes}", fontSize = 12.sp, color = Color(0xFF788895))
                            val visitLabs = labOrders.filter { it.clinicalVisitId == visit.id }
                            val visitRadiology = radiologyOrders.filter { it.clinicalVisitId == visit.id }
                            val visitPrescriptions = prescriptionOrders.filter { it.clinicalVisitId == visit.id }
                            if (visitLabs.isNotEmpty() || visitRadiology.isNotEmpty() || visitPrescriptions.isNotEmpty()) {
                                HorizontalDivider()
                                Text("الطلبات والوصفات المرتبطة بهذه الزيارة", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF356D69))
                                visitLabs.forEach { order ->
                                    Text("مختبر • ${order.testName} • ${order.status.ifBlank { "غير محدد" }}", fontSize = 12.sp)
                                    if (order.result.isNotBlank()) Text("النتيجة: ${order.result}", fontSize = 11.sp, color = Color(0xFF788895))
                                }
                                visitRadiology.forEach { order ->
                                    Text("أشعة • ${order.imagingType} • ${order.status.ifBlank { "غير محدد" }}", fontSize = 12.sp)
                                    if (order.report.isNotBlank()) Text("التقرير: ${order.report}", fontSize = 11.sp, color = Color(0xFF788895))
                                }
                                visitPrescriptions.forEach { order ->
                                    Text("وصفة • ${order.medicationName} • ${order.dose}", fontSize = 12.sp)
                                    Text("${order.frequencyAndDuration} • ${order.status}", fontSize = 11.sp, color = Color(0xFF788895))
                                }
                            }
                            TextButton(onClick = {
                                editingVisit = visit
                                patient = visit.patientName
                                doctor = visit.doctorName
                                selectedPatientId = visit.patientId ?: patients.firstOrNull { it.fullName.equals(visit.patientName, ignoreCase = true) }?.id
                                selectedDoctorId = visit.doctorId ?: doctors.firstOrNull { it.fullName.equals(visit.doctorName, ignoreCase = true) }?.id
                                visitAt = visit.visitAt
                                complaint = visit.chiefComplaint
                                diagnosis = visit.diagnosis
                                notes = visit.notes
                                message = "أنت تعدّل زيارة محفوظة. لن تُحفظ التغييرات إلا عند الضغط على حفظ التعديلات."
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text("تعديل الزيارة")
                            }
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("سجلات أخرى قد تخص المريض", fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                            Text(
                                "هذه قائمة استدلالية مبنية على تطابق الاسم في السجلات العامة، وليست بديلًا عن ربط كل سجل بمعرّف المريض.",
                                fontSize = 11.sp, color = Color(0xFF9A6B28)
                            )
                            if (relatedRecords.isEmpty()) {
                                Text("لا توجد سجلات عامة أخرى تطابق اسم هذا المريض.", fontSize = 12.sp, color = Color(0xFF788895))
                            } else {
                                relatedRecords.take(30).forEach { record ->
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("${record.category} • ${record.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(record.details.take(240), fontSize = 12.sp, color = Color(0xFF788895))
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
        val selectedVisit = activeVisit
        if (selectedVisit != null) {
            val patientProfile = patients.firstOrNull { it.id == selectedVisit.patientId }
            val visitRecord = allRecords.firstOrNull { it.id == selectedVisit.sourceRecordId }
            AlertDialog(
                onDismissRequest = { activeVisit = null },
                title = { Text(when (dialogType) {
                    "details" -> "ملف المريض • ${selectedVisit.patientName}"
                    "diagnosis" -> "تسجيل التشخيص"
                    "lab" -> "الفحوصات المطلوبة"
                    "medication" -> "الأدوية المطلوبة"
                    "history" -> "السجل الطبي السابق"
                    "followup" -> "موعد المراجعة"
                    else -> "ملف المريض"
                }) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (dialogType) {
                            "details" -> {
                                Text("الطبيب: ${selectedVisit.doctorName}")
                                Text("تاريخ الزيارة: ${selectedVisit.visitAt}")
                                Text("رقم الملف: ${patientProfile?.fileNumber ?: "غير متوفر"}")
                                Text("الهاتف: ${patientProfile?.phone ?: "غير متوفر"}")
                                Text("العمر: ${patientProfile?.age ?: "غير متوفر"}")
                                Text("بيانات إضافية: ${patientProfile?.notes?.ifBlank { "غير مدخلة" } ?: "غير متوفرة"}")
                                Text("الشكوى: ${selectedVisit.chiefComplaint}")
                                Text("التشخيص الحالي: ${selectedVisit.diagnosis.ifBlank { "لم يسجل بعد" }}")
                                Text("ملاحظات الزيارة: ${selectedVisit.notes.ifBlank { "لا توجد" }}")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { dialogType = "diagnosis"; dialogValue = selectedVisit.diagnosis }) { Text("التشخيص") }
                                    OutlinedButton(onClick = { dialogType = "lab"; dialogValue = ""; dialogExtra = "" }) { Text("الفحوصات") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { dialogType = "medication"; dialogValue = ""; dialogExtra = "" }) { Text("الأدوية") }
                                    OutlinedButton(onClick = { dialogType = "history" }) { Text("السجل السابق") }
                                }
                                OutlinedButton(onClick = { dialogType = "followup"; dialogValue = ""; dialogExtra = "" }, modifier = Modifier.fillMaxWidth()) { Text("موعد المراجعة") }
                            }
                            "diagnosis" -> OutlinedTextField(dialogValue, { dialogValue = it }, label = { Text("التشخيص") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                            "lab" -> {
                                OutlinedTextField(dialogValue, { dialogValue = it }, label = { Text("اسم الفحص المطلوب") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                OutlinedTextField(dialogExtra, { dialogExtra = it }, label = { Text("ملاحظات للمختبر (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            }
                            "medication" -> {
                                OutlinedTextField(dialogValue, { dialogValue = it }, label = { Text("اسم الدواء | الجرعة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                OutlinedTextField(dialogExtra, { dialogExtra = it }, label = { Text("التكرار والمدة والتعليمات") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            }
                            "history" -> {
                                val previousVisits = visits.filter { it.patientId == selectedVisit.patientId && it.id != selectedVisit.id }.sortedByDescending { it.createdAt }
                                if (patientProfile?.notes?.isNotBlank() == true) Text("التاريخ الطبي الأساسي: ${patientProfile.notes}")
                                if (previousVisits.isEmpty()) Text("لا توجد زيارات سابقة مسجلة.")
                                previousVisits.forEach { previous ->
                                    HorizontalDivider()
                                    Text("${previous.visitAt} • ${previous.doctorName}", fontWeight = FontWeight.SemiBold)
                                    Text("الشكوى: ${previous.chiefComplaint}")
                                    Text("التشخيص: ${previous.diagnosis.ifBlank { "غير مسجل" }}")
                                    if (previous.notes.isNotBlank()) Text("ملاحظات: ${previous.notes}")
                                }
                            }
                            "followup" -> {
                                OutlinedTextField(dialogValue, { dialogValue = it }, label = { Text("تاريخ المراجعة YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(dialogExtra, { dialogExtra = it }, label = { Text("وقت المراجعة / التعليمات") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (dialogType != "details" && dialogType != "history") {
                        TextButton(enabled = !dialogSaving, onClick = {
                            if (dialogValue.isBlank()) return@TextButton
                            dialogSaving = true
                            scope.launch {
                                try {
                                    when (dialogType) {
                                        "diagnosis" -> {
                                            val oldDetails = visitRecord?.details.orEmpty()
                                            val nextLines = oldDetails.lineSequence().filterNot { it.startsWith("التشخيص: ") }.toMutableList()
                                            nextLines.add("التشخيص: ${dialogValue.trim()}")
                                            val linkedPatientId = selectedVisit.patientId
                                                ?: patients.firstOrNull { it.fullName.equals(selectedVisit.patientName, ignoreCase = true) }?.id
                                            val linkedDoctorId = selectedVisit.doctorId
                                                ?: doctors.firstOrNull { it.fullName.equals(selectedVisit.doctorName, ignoreCase = true) }?.id
                                            if (linkedPatientId == null || linkedDoctorId == null) {
                                                throw IllegalStateException("لا يمكن تحديث الزيارة دون ربط المريض والطبيب.")
                                            }
                                            repository.updateClinicalVisitRecord(
                                                selectedVisit.sourceRecordId, selectedVisit.patientName,
                                                nextLines.joinToString("\n"), linkedPatientId, linkedDoctorId
                                            )
                                            activeVisit = null
                                        }
                                        "lab" -> repository.saveRecord(HospitalRecord(
                                            category = "lab", title = "LAB-${System.currentTimeMillis()}",
                                            details = "اسم المريض: ${selectedVisit.patientName}\nاسم الفحص: ${dialogValue.trim()}\nحالة الفحص: مطلوب\nالنتيجة: ${dialogExtra.trim()}\nمعرّف الزيارة: ${selectedVisit.id}",
                                            createdAt = System.currentTimeMillis()
                                        ))
                                        "medication" -> {
                                            val parts = dialogValue.split("|", limit = 2)
                                            repository.saveRecord(HospitalRecord(
                                                category = "prescriptions", title = parts.first().trim(),
                                                details = "اسم المريض: ${selectedVisit.patientName}\nاسم الطبيب: ${selectedVisit.doctorName}\nاسم الدواء: ${parts.first().trim()}\nالجرعة: ${parts.getOrElse(1) { "" }.trim()}\nالتكرار والمدة: ${dialogExtra.trim()}\nالحالة: مطلوب\nتعليمات إضافية: ${dialogExtra.trim()}\nمعرّف الزيارة: ${selectedVisit.id}",
                                                createdAt = System.currentTimeMillis()
                                            ))
                                        }
                                        "followup" -> repository.saveRecord(HospitalRecord(
                                            category = "appointments", title = selectedVisit.patientName,
                                            details = "اسم المريض: ${selectedVisit.patientName}\nاسم الطبيب: ${selectedVisit.doctorName}\nالتاريخ والوقت: ${dialogValue.trim()} ${dialogExtra.trim()}\nسبب الزيارة: مراجعة بعد الزيارة رقم ${selectedVisit.id}",
                                            createdAt = System.currentTimeMillis()
                                        ))
                                    }
                                    if (dialogType != "diagnosis") dialogType = "details"
                                } catch (e: Exception) {
                                    dialogExtra = "تعذر الحفظ؛ تحقق من البيانات ثم أعد المحاولة."
                                } finally { dialogSaving = false }
                            }
                        }) { Text(if (dialogSaving) "جارٍ الحفظ..." else "حفظ") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (dialogType == "details") activeVisit = null else dialogType = "details" }) {
                        Text(if (dialogType == "details") "إغلاق" else "رجوع")
                    }
                }
            )
        }
    }
}
