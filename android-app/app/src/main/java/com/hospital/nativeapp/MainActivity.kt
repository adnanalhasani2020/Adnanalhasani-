package com.hospital.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection

private data class Patient(
    val id: String,
    val name: String,
    val age: String,
    val sex: String,
    val phone: String,
    val department: String,
    val complaint: String,
    val status: String
)

private enum class ClinicalAction(val title: String, val hint: String) {
    DIAGNOSIS("التشخيص", "اكتب التشخيص المعتمد من الطبيب"),
    TESTS("الفحوصات المطلوبة", "اكتب اسم الفحص، ويمكن إضافة أكثر من فحص"),
    MEDICINES("الأدوية المطلوبة", "اسم الدواء والجرعة والتكرار والمدة"),
    HISTORY("السجل الطبي السابق", "عرض الزيارات والتشخيصات والعلاجات السابقة"),
    FOLLOW_UP("موعد المراجعة", "حدد تاريخ ووقت المراجعة")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HospitalApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HospitalApp() {
    var currentTab by remember { mutableStateOf(0) }
    var selectedPatient by remember { mutableStateOf<Patient?>(null) }
    var action by remember { mutableStateOf<ClinicalAction?>(null) }
    var search by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("العيادات العامة") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("ذكر") }
    var complaint by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("واجهة أندرويد تجريبية — لم يتم ربطها بالخادم بعد") }

    val patients = remember {
        mutableStateListOf(
            Patient("DEMO-001", "مريض تجريبي ١", "35", "ذكر", "—", "العيادات العامة", "مراجعة عامة", "بانتظار الطبيب"),
            Patient("DEMO-002", "مريضة تجريبية ٢", "28", "أنثى", "—", "الأطفال", "ارتفاع حرارة", "بانتظار الطبيب")
        )
    }
    val filtered = patients.filter {
        (it.name.contains(search, true) || it.id.contains(search, true)) &&
            (currentTab == 0 || it.department == department || currentTab == 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("نظام المستشفى", fontWeight = FontWeight.Bold)
                        Text(if (currentTab == 0) "الاستقبال والتحصيل" else "عيادة الطبيب", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0; selectedPatient = null },
                    icon = { Text("＋") },
                    label = { Text("الاستقبال") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1; selectedPatient = null },
                    icon = { Text("✚") },
                    label = { Text("الطبيب") }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(notice, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }

            if (currentTab == 0) {
                Text("تسجيل مريض جديد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(patientName, { patientName = it }, label = { Text("الاسم الكامل *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(age, { age = it }, label = { Text("العمر") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") }, modifier = Modifier.weight(1.4f), singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الجنس")
                    FilterChip(selected = sex == "ذكر", onClick = { sex = "ذكر" }, label = { Text("ذكر") })
                    FilterChip(selected = sex == "أنثى", onClick = { sex = "أنثى" }, label = { Text("أنثى") })
                }
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(department, {}, readOnly = true, label = { Text("القسم / العيادة") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("العيادات العامة", "الأطفال", "الباطنية", "الأسنان", "الطوارئ").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { department = it; expanded = false })
                        }
                    }
                }
                OutlinedTextField(complaint, { complaint = it }, label = { Text("سبب الزيارة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(
                    onClick = {
                        if (patientName.isBlank()) notice = "أدخل اسم المريض أولاً"
                        else {
                            val p = Patient("LOCAL-${patients.size + 1}", patientName.trim(), age, sex, phone, department, complaint, "بانتظار الطبيب")
                            patients.add(0, p)
                            notice = "أضيف المريض إلى القائمة المحلية فقط؛ الحفظ في قاعدة البيانات غير مفعل بعد"
                            patientName = ""; age = ""; phone = ""; complaint = ""
                            currentTab = 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تسجيل وإحالة إلى العيادة") }
                HorizontalDivider()
            } else {
                Text("قائمة انتظار الطبيب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(search, { search = it }, label = { Text("ابحث باسم المريض أو رقمه") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("اضغط على اسم المريض لفتح ملف الزيارة", style = MaterialTheme.typography.bodySmall)
            }

            if (currentTab == 0) {
                Text("المرضى المسجلون في هذه الجلسة", style = MaterialTheme.typography.titleSmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { patient ->
                    PatientCard(patient) {
                        selectedPatient = patient
                        if (currentTab == 0) currentTab = 1
                    }
                }
            }
        }
    }

    selectedPatient?.let { patient ->
        PatientDetailsDialog(
            patient = patient,
            onDismiss = { selectedPatient = null },
            onAction = { action = it }
        )
    }

    action?.let { selectedAction ->
        ClinicalActionDialog(
            action = selectedAction,
            patient = selectedPatient,
            onDismiss = { action = null },
            onSave = { value ->
                notice = when (selectedAction) {
                    ClinicalAction.HISTORY -> "السجل التجريبي فقط: لا توجد بيانات سريرية محفوظة لهذا المريض"
                    else -> "لم يتم الحفظ: ربط قاعدة البيانات بالخدمة غير منفذ بعد. المدخل: $value"
                }
                action = null
            }
        )
    }
}

@Composable
private fun PatientCard(patient: Patient, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(patient.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                AssistChip(onClick = onClick, label = { Text(patient.status) })
            }
            Text("رقم الملف: ${patient.id}   •   ${patient.age} سنة   •   ${patient.sex}", style = MaterialTheme.typography.bodySmall)
            Text("القسم: ${patient.department}", style = MaterialTheme.typography.bodySmall)
            if (patient.complaint.isNotBlank()) Text("سبب الزيارة: ${patient.complaint}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PatientDetailsDialog(
    patient: Patient,
    onDismiss: () -> Unit,
    onAction: (ClinicalAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(patient.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("رقم الملف: ${patient.id}")
                Text("العمر: ${patient.age}    الجنس: ${patient.sex}")
                Text("الهاتف: ${patient.phone}")
                Text("القسم: ${patient.department}")
                Text("سبب الزيارة: ${patient.complaint.ifBlank { "غير محدد" }}")
                HorizontalDivider()
                ClinicalAction.entries.forEach { item ->
                    OutlinedButton(onClick = { onAction(item) }, modifier = Modifier.fillMaxWidth()) {
                        Text(item.title)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
private fun ClinicalActionDialog(
    action: ClinicalAction,
    patient: Patient?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(action, patient?.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${action.title} — ${patient?.name.orEmpty()}") },
        text = {
            if (action == ClinicalAction.HISTORY) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سيظهر هنا السجل المرتبط بقاعدة البيانات بعد ربط الواجهة بالخادم.")
                    Text("لا توجد بيانات سابقة محملة في النسخة الحالية.")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.hint, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(action.title) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = if (action == ClinicalAction.DIAGNOSIS) 3 else 4
                    )
                }
            }
        },
        confirmButton = {
            if (action != ClinicalAction.HISTORY) {
                Button(onClick = { if (value.isNotBlank()) onSave(value.trim()) }) { Text("حفظ") }
            } else TextButton(onClick = onDismiss) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
