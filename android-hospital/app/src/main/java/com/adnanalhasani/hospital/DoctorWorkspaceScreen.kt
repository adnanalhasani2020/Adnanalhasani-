package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalInformation
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("شاشة الطبيب", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        Text("تسجيل زيارة طبية مرتبطة بسجل المريض والطبيب، مع فصل الشكوى والتشخيص والملاحظات.", fontSize = 12.sp, color = Color(0xFF788895))
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("تسجيل زيارة جديدة", fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                        if (patients.isEmpty() || doctors.isEmpty()) {
                            Text("يجب إنشاء سجل المريض والطبيب أولاً ثم اختيارهما من القوائم لضمان ربط الزيارة بمعرّف صحيح في قاعدة البيانات.", color = Color(0xFF9A6B28), fontSize = 12.sp)
                        }
                        var patientExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = patientExpanded, onExpandedChange = { patientExpanded = !patientExpanded }) {
                            OutlinedTextField(value = patient, onValueChange = {}, readOnly = true, label = { Text("اختر المريض *") }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                            ExposedDropdownMenu(expanded = patientExpanded, onDismissRequest = { patientExpanded = false }) {
                                patients.forEach { option -> DropdownMenuItem(text = { Text("${option.fullName} • ${option.fileNumber}") }, onClick = { patient = option.fullName; selectedPatientId = option.id; patientExpanded = false }) }
                            }
                        }
                        var doctorExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = doctorExpanded, onExpandedChange = { doctorExpanded = !doctorExpanded }) {
                            OutlinedTextField(value = doctor, onValueChange = {}, readOnly = true, label = { Text("اختر الطبيب *") }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                            ExposedDropdownMenu(expanded = doctorExpanded, onDismissRequest = { doctorExpanded = false }) {
                                doctors.forEach { option -> DropdownMenuItem(text = { Text("${option.fullName} • ${option.employeeNumber}") }, onClick = { doctor = option.fullName; selectedDoctorId = option.id; doctorExpanded = false }) }
                            }
                        }
                        OutlinedTextField(value = visitAt, onValueChange = { visitAt = it }, label = { Text("تاريخ ووقت الزيارة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = complaint, onValueChange = { complaint = it }, label = { Text("الشكوى الرئيسية *") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(value = diagnosis, onValueChange = { diagnosis = it }, label = { Text("التشخيص") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات وخطة المتابعة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Button(
                            onClick = {
                                if (selectedPatientId == null || selectedDoctorId == null || patient.isBlank() || doctor.isBlank() || complaint.isBlank()) {
                                    message = "اختر مريضًا وطبيبًا مسجلين من القوائم وأدخل الشكوى الرئيسية."
                                } else {
                                    scope.launch {
                                        saving = true
                                        try {
                                            val details = listOf(
                                                "اسم المريض: $patient", "اسم الطبيب: $doctor", "تاريخ الزيارة: $visitAt",
                                                "الشكوى الرئيسية: $complaint", "التشخيص: $diagnosis", "ملاحظات: $notes"
                                            ).joinToString("\n")
                                            repository.saveClinicalVisit(HospitalRecord(category = "encounters", title = patient, details = details), selectedPatientId!!, selectedDoctorId!!)
                                            complaint = ""; diagnosis = ""; notes = ""
                                            message = "تم حفظ الزيارة وربطها بمعرّفي المريض والطبيب في قاعدة البيانات."
                                        } catch (e: Exception) {
                                            message = "تعذر حفظ الزيارة. تحقق من البيانات وحاول مجددًا."
                                        } finally { saving = false }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving
                        ) {
                            Icon(Icons.Default.MedicalInformation, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (saving) "جارٍ الحفظ..." else "حفظ الزيارة")
                        }
                        message?.let { Text(it, color = if (it.startsWith("تم")) Color(0xFF548F88) else Color(0xFFC97979), fontSize = 12.sp) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الزيارات السابقة", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF263B4D))
                    Text("${visits.size} زيارة", color = Color(0xFF788895), fontSize = 12.sp)
                }
            }
            if (visits.isEmpty()) {
                item { Text("لا توجد زيارات طبية محفوظة حتى الآن.", color = Color(0xFF788895), fontSize = 13.sp) }
            } else {
                items(visits, key = { it.id }) { visit ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(visit.patientName.ifBlank { "مريض غير محدد" }, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                            Text("الطبيب: ${visit.doctorName.ifBlank { "غير محدد" }} • ${visit.visitAt}", color = Color(0xFF788895), fontSize = 11.sp)
                            if (visit.chiefComplaint.isNotBlank()) Text("الشكوى: ${visit.chiefComplaint}", fontSize = 13.sp)
                            if (visit.diagnosis.isNotBlank()) Text("التشخيص: ${visit.diagnosis}", fontSize = 13.sp)
                            if (visit.notes.isNotBlank()) Text("ملاحظات: ${visit.notes}", fontSize = 12.sp, color = Color(0xFF788895))
                        }
                    }
                }
            }
        }
    }
}
