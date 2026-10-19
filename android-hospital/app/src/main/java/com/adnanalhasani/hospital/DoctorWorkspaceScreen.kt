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
                                                    repository.updateRecord(currentEdit.sourceRecordId, patient, visitDetails())
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
                            Text("الطبيب: ${visit.doctorName.ifBlank { "غير محدد" }} • ${visit.visitAt}", color = Color(0xFF788895), fontSize = 11.sp)
                            if (visit.chiefComplaint.isNotBlank()) Text("الشكوى: ${visit.chiefComplaint}", fontSize = 13.sp)
                            if (visit.diagnosis.isNotBlank()) Text("التشخيص: ${visit.diagnosis}", fontSize = 13.sp)
                            if (visit.notes.isNotBlank()) Text("ملاحظات: ${visit.notes}", fontSize = 12.sp, color = Color(0xFF788895))
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
    }
}
