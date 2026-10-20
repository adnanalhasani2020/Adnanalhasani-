package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun labStatusLabel(status: String): String = when (status) {
    "collected" -> "تم جمع العينة"
    "received" -> "استُلمت في المختبر"
    "processing" -> "قيد المعالجة"
    "completed" -> "مكتملة"
    "rejected" -> "مرفوضة"
    else -> status
}

private fun labTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(value))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaboratoryScreen() {
    val context = LocalContext.current
    val database = remember(context) { HospitalDatabase.get(context) }
    val dao = remember(database) { database.hospitalDao() }
    val scope = rememberCoroutineScope()
    val orders by dao.labOrders().collectAsState(initial = emptyList())
    val specimens by dao.labSpecimens().collectAsState(initial = emptyList())
    val settings by dao.labWorkflowSettings().collectAsState(initial = emptyList())
    val specimenGuide by dao.referenceItems("lab_specimen_guide").collectAsState(initial = emptyList())

    LaunchedEffect(database) {
        withContext(Dispatchers.IO) {
            if (dao.referenceCount("lab_specimen_guide") == 0) {
                val guideDefaults = listOf(
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "دم كامل EDTA", description = "الوعاء: أنبوب EDTA المناسب للاختبار. امزج بقلب الأنبوب برفق وفق تعليمات الشركة. لا تقبل العينة المتجلطة أو غير الموسومة/المتعارضة. التخزين والكمية يعتمدان على الاختبار ودليل المختبر المحلي."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "مصل", description = "الوعاء: أنبوب مصل أو فاصل مصل مناسب للاختبار. اترك التخثر والطرد المركزي حسب الإجراء المعتمد. تحقق من الانحلال أو التلوث وفق معايير الاختبار واتبع متطلبات النقل والتخزين الخاصة بالفحص."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "بلازما السيترات", description = "الوعاء: أنبوب سيترات مخصص لاختبارات التخثر. مستوى التعبئة ونسبة الدم إلى مانع التخثر حاسمان؛ لا تستخدم أنبوبًا ناقص التعبئة أو متجلطًا. اتبع إجراء المختبر المعتمد."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "بول", description = "الوعاء: حاوية نظيفة/معقمة حسب نوع الفحص. اجمع عينة منتصف المجرى إذا طلب الإجراء ذلك، وتجنب التلوث. انقلها سريعًا؛ وإذا تأخر النقل فاتبع سياسة التبريد المعتمدة للاختبار."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "براز", description = "الوعاء: حاوية نظيفة محكمة الإغلاق ومناسبة للفحص. تجنب اختلاط العينة بالبول أو الماء. نوع العينة ووقت النقل ووسيط الحفظ تتحدد وفق الفحص المطلوب وتعليمات المختبر."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "مسحة", description = "اختر نوع المسحة ووسط النقل الموافقين لموضع العينة والاختبار المطلوب؛ لا يوجد وسط واحد مناسب لكل الفحوص. تجنب جفاف العينة، وتحقق من الوسم والنقل والمدة وفق تعليمات الاختبار."),
                    ReferenceCatalogEntity(category = "lab_specimen_guide", name = "بلغم", description = "الوعاء: حاوية معقمة محكمة الإغلاق. وضّح للمريض أن المطلوب بلغم من الجهاز التنفسي لا لعابًا. تجنب التلوث، وانقل العينة حسب تعليمات الفحص وسياسة المختبر.")
                )
                guideDefaults.forEach { dao.insertReference(it) }
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var statusMenuExpanded by remember { mutableStateOf(false) }
    val filteredSpecimens by dao.searchLabSpecimens(searchQuery.trim(), statusFilter).collectAsState(initial = emptyList())

    var selectedOrderId by remember { mutableStateOf<Long?>(null) }
    var orderMenuExpanded by remember { mutableStateOf(false) }
    var specimenTypeMenuExpanded by remember { mutableStateOf(false) }
    var specimenCode by remember { mutableStateOf("") }
    var responsibleEmployee by remember { mutableStateOf("") }
    var specimenType by remember { mutableStateOf("") }
    var rejectionReason by remember { mutableStateOf("") }
    var requireReceipt by remember { mutableStateOf(true) }
    var specimenTypes by remember { mutableStateOf("دم، بول، مسحة، مصل، براز") }
    var rejectionReasons by remember { mutableStateOf("عينة غير كافية، عينة متجلطة، وعاء غير مناسب، بيانات غير مطابقة") }
    val specimenTypeOptions = (specimenGuide.filter { it.active }.map { it.name } +
        specimenTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }).distinct()
    val selectedSpecimenGuide = specimenGuide.firstOrNull { it.active && it.name.equals(specimenType, ignoreCase = true) }
    var turnaroundHours by remember { mutableStateOf("24") }
    var settingsExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        settings.firstOrNull { it.key == "require_receipt_confirmation" }?.let {
            requireReceipt = it.value.equals("true", ignoreCase = true)
        }
        settings.firstOrNull { it.key == "specimen_types" }?.let { specimenTypes = it.value }
        settings.firstOrNull { it.key == "rejection_reasons" }?.let { rejectionReasons = it.value }
        settings.firstOrNull { it.key == "specimen_turnaround_hours" }?.let {
            turnaroundHours = it.value.toIntOrNull()?.coerceIn(1, 720)?.toString() ?: "24"
        }
        settings.firstOrNull { it.key == "last_operator" }?.let { if (responsibleEmployee.isBlank()) responsibleEmployee = it.value }
        if (specimenType.isBlank()) specimenType = specimenTypes.split(",").firstOrNull()?.trim().orEmpty()
    }

    val selectedOrder = orders.firstOrNull { it.id == selectedOrderId }
    val pendingCount = specimens.count { it.status !in listOf("completed", "rejected") }
    val now = System.currentTimeMillis()
    val turnaroundLimitMillis = (turnaroundHours.toIntOrNull()?.coerceIn(1, 720) ?: 24) * 60L * 60L * 1000L
    val overdueCount = specimens.count {
        it.status !in listOf("completed", "rejected") && now - it.collectedAt >= turnaroundLimitMillis
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("متابعة العينات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("تسجيل العينة وربطها بطلب الفحص، مع توثيق الاستلام ومراحل المعالجة في SQLite.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("إجمالي العينات: ${specimens.size}", fontWeight = FontWeight.SemiBold)
                        Text("قيد المتابعة: $pendingCount", color = MaterialTheme.colorScheme.primary)
                        Text("تجاوزت الزمن المستهدف: $overdueCount", color = if (overdueCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Text("الزمن المستهدف الحالي: ${turnaroundHours.toIntOrNull()?.coerceIn(1, 720) ?: 24} ساعة لكل عينة غير مكتملة.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("دليل العينات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("إرشادات تشغيلية عامة. المرجع النهائي هو إجراء المختبر المعتمد وتعليمات الاختبار والشركة المصنّعة؛ لا تعتمد هذه الملاحظات وحدها لتحديد كمية أو مدة حفظ العينة.")
                    if (specimenGuide.none { it.active }) {
                        Text("لا توجد عناصر دليل نشطة. يمكن للمدير تعديل الدليل من القوائم المرجعية.")
                    } else {
                        specimenGuide.filter { it.active }.forEach { guide ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(guide.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                Text(guide.description.ifBlank { "لم يضف المدير تعليمات تفصيلية لهذا النوع بعد." }, style = MaterialTheme.typography.bodySmall)
                            }
                            Divider()
                        }
                    }
                    Text("يمكن إضافة الأنواع وتعديل إرشاداتها أو تعطيلها من شاشة «إدارة القوائم المرجعية» ضمن «دليل جمع العينات».", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("تسجيل عينة جديدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (orders.isEmpty()) {
                        Text("لا توجد طلبات فحوصات بعد. أنشئ طلب فحص من قسم المختبر أولاً.")
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = orderMenuExpanded,
                            onExpandedChange = { orderMenuExpanded = !orderMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedOrder?.let { "#${it.id} • ${it.patientName} • ${it.testName}" } ?: "اختر طلب الفحص",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("طلب الفحص") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = orderMenuExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = orderMenuExpanded,
                                onDismissRequest = { orderMenuExpanded = false }
                            ) {
                                orders.filter { it.status != "cancelled" }.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text("#${order.id} • ${order.patientName} • ${order.testName}") },
                                        onClick = { selectedOrderId = order.id; orderMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = responsibleEmployee,
                            onValueChange = { responsibleEmployee = it },
                            label = { Text("اسم الموظف المسؤول") },
                            supportingText = { Text("يُسجّل الاسم مع كل انتقال في سجل العينة.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = specimenCode,
                            onValueChange = { specimenCode = it },
                            label = { Text("رمز العينة الفريد") },
                            supportingText = { Text("يُرفض الرمز المكرر بواسطة قيد SQLite.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenuBox(
                            expanded = specimenTypeMenuExpanded,
                            onExpandedChange = { specimenTypeMenuExpanded = !specimenTypeMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = specimenType,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("نوع العينة") },
                                supportingText = { Text("اختر النوع من دليل العينات أو من الأنواع التشغيلية التي أعدّها المدير.") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = specimenTypeMenuExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = specimenTypeMenuExpanded,
                                onDismissRequest = { specimenTypeMenuExpanded = false }
                            ) {
                                specimenTypeOptions.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type) },
                                        onClick = { specimenType = type; specimenTypeMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        selectedSpecimenGuide?.let { guide ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("إرشادات النوع المحدد: ${guide.name}", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        guide.description.ifBlank { "لم يضف المدير تعليمات تفصيلية لهذا النوع بعد." },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "تحقق من إجراء المختبر المعتمد وتعليمات الفحص قبل الجمع أو المعالجة.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedOrder != null && specimenCode.isNotBlank() && specimenType.isNotBlank() && responsibleEmployee.isNotBlank(),
                            onClick = {
                                val order = selectedOrder ?: return@Button
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val collectedAt = System.currentTimeMillis()
                                            dao.registerLabSpecimen(
                                                LabSpecimenEntity(
                                                    labOrderId = order.id,
                                                    specimenCode = specimenCode.trim(),
                                                    specimenType = specimenType.trim(),
                                                    status = "collected",
                                                    collectedAt = collectedAt,
                                                    updatedAt = collectedAt
                                                ),
                                                responsibleEmployee.trim()
                                            )
                                            dao.saveLabWorkflowSetting(
                                                LabWorkflowSettingEntity("last_operator", responsibleEmployee.trim(), collectedAt)
                                            )
                                        }
                                    }
                                    message = if (result.isSuccess) {
                                        specimenCode = ""
                                        "تم حفظ العينة وربطها بطلب الفحص."
                                    } else {
                                        "تعذر حفظ العينة. تأكد من عدم تكرار الرمز ومن سلامة البيانات."
                                    }
                                }
                            }
                        ) { Text("حفظ العينة في SQLite") }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("إعدادات سير عمل المختبر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                            Text(if (settingsExpanded) "إخفاء" else "إعدادات المدير")
                        }
                    }
                    Text("هذه خيارات تشغيلية يحددها مدير المنشأة، ولا تغيّر النتائج الطبية أو الأولويات السريرية.")
                    if (settingsExpanded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = requireReceipt, onCheckedChange = { requireReceipt = it })
                            Column {
                                Text("اشتراط تسجيل استلام العينة")
                                Text("عند التفعيل، يلزم تسجيل الاستلام قبل بدء المعالجة.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = specimenTypes,
                            onValueChange = { specimenTypes = it },
                            label = { Text("أنواع العينات المتاحة") },
                            supportingText = { Text("افصل الخيارات بفواصل عربية أو إنجليزية.") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        OutlinedTextField(
                            value = rejectionReasons,
                            onValueChange = { rejectionReasons = it },
                            label = { Text("أسباب رفض العينة المقترحة") },
                            supportingText = { Text("تُعرض كمرجع للفريق؛ ويظل سبب الرفض موثقاً لكل عينة.") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        OutlinedTextField(
                            value = turnaroundHours,
                            onValueChange = { input -> turnaroundHours = input.filter(Char::isDigit).take(3) },
                            label = { Text("الحد المستهدف لإنجاز العينة (بالساعات)") },
                            supportingText = { Text("من 1 إلى 720 ساعة. تظهر إشارة عند تجاوز المدة؛ هذا تنبيه تشغيلي وليس أولوية طبية.") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = (turnaroundHours.toIntOrNull() ?: 0) in 1..720,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        dao.saveLabWorkflowSetting(LabWorkflowSettingEntity("require_receipt_confirmation", requireReceipt.toString(), System.currentTimeMillis()))
                                        dao.saveLabWorkflowSetting(LabWorkflowSettingEntity("specimen_types", specimenTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", "), System.currentTimeMillis()))
                                        dao.saveLabWorkflowSetting(LabWorkflowSettingEntity("rejection_reasons", rejectionReasons.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", "), System.currentTimeMillis()))
                                        dao.saveLabWorkflowSetting(LabWorkflowSettingEntity("specimen_turnaround_hours", turnaroundHours.toInt().coerceIn(1, 720).toString(), System.currentTimeMillis()))
                                        if (responsibleEmployee.isNotBlank()) dao.saveLabWorkflowSetting(LabWorkflowSettingEntity("last_operator", responsibleEmployee.trim(), System.currentTimeMillis()))
                                    }
                                    message = "تم حفظ إعدادات المختبر في قاعدة SQLite المحلية."
                                }
                            }
                        ) { Text("حفظ إعدادات المختبر") }
                    }
                }
            }
        }
        if (message != null) {
            item {
                Text(message.orEmpty(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Text("سجل العينات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("ابحث برمز العينة أو نوعها أو اسم المريض أو الفحص أو رقم الطلب.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("البحث في العينات") },
                placeholder = { Text("رمز العينة، المريض، الفحص...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            ExposedDropdownMenuBox(
                expanded = statusMenuExpanded,
                onExpandedChange = { statusMenuExpanded = !statusMenuExpanded }
            ) {
                OutlinedTextField(
                    value = when (statusFilter) {
                        "all" -> "كل الحالات"
                        "collected" -> "تم جمع العينة"
                        "received" -> "استُلمت في المختبر"
                        "processing" -> "قيد المعالجة"
                        "completed" -> "مكتملة"
                        "rejected" -> "مرفوضة"
                        else -> statusFilter
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("تصفية حسب الحالة") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = statusMenuExpanded,
                    onDismissRequest = { statusMenuExpanded = false }
                ) {
                    listOf(
                        "all" to "كل الحالات",
                        "collected" to "تم جمع العينة",
                        "received" to "استُلمت في المختبر",
                        "processing" to "قيد المعالجة",
                        "completed" to "مكتملة",
                        "rejected" to "مرفوضة"
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { statusFilter = value; statusMenuExpanded = false }
                        )
                    }
                }
            }
            Text("النتائج المطابقة: ${filteredSpecimens.size} من أصل ${specimens.size}", style = MaterialTheme.typography.bodySmall)
        }
        if (filteredSpecimens.isEmpty()) {
            item {
                Text(
                    if (specimens.isEmpty()) "لا توجد عينات مسجلة حتى الآن."
                    else "لا توجد عينات تطابق البحث أو الحالة المحددة."
                )
            }
        }
        items(filteredSpecimens, key = { it.id }) { specimen ->
            val order = orders.firstOrNull { it.id == specimen.labOrderId }
            val events by dao.labSpecimenEvents(specimen.id).collectAsState(initial = emptyList())
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${specimen.specimenCode} • ${specimen.specimenType}", fontWeight = FontWeight.Bold)
                    Text("طلب #${specimen.labOrderId} • ${order?.patientName ?: "طلب غير متاح"} • ${order?.testName.orEmpty()}")
                    Text("الحالة: ${labStatusLabel(specimen.status)}")

                    val specimenAgeMillis = (now - specimen.collectedAt).coerceAtLeast(0L)
                    val specimenOverdue = specimen.status !in listOf("completed", "rejected") && specimenAgeMillis >= turnaroundLimitMillis
                    if (specimenOverdue) {
                        val ageHours = specimenAgeMillis / (60L * 60L * 1000L)
                        Text(
                            "تنبيه: تجاوزت العينة الزمن المستهدف (" + ageHours + " ساعة من أصل " + (turnaroundHours.toIntOrNull()?.coerceIn(1, 720) ?: 24) + ").",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text("تاريخ الجمع: ${labTime(specimen.collectedAt)}", style = MaterialTheme.typography.bodySmall)
                    specimen.receivedAt?.let { Text("تاريخ الاستلام: ${labTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    if (specimen.rejectionReason.isNotBlank()) Text("سبب الرفض: ${specimen.rejectionReason}")
                    if (events.isNotEmpty()) {
                        Text("سجل حركة العينة", fontWeight = FontWeight.SemiBold)
                        events.take(5).forEach { event ->
                            Text(
                                "• ${labStatusLabel(event.status)} — ${labTime(event.occurredAt)} — المسؤول: ${event.actor}" +
                                    if (event.note.isBlank()) "" else " — ${event.note}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (specimen.status !in listOf("completed", "rejected")) {
                        Divider()
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (specimen.status == "collected") {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val at = System.currentTimeMillis()
                                        val changed = withContext(Dispatchers.IO) {
                                            dao.transitionLabSpecimen(specimen.id, specimen.status, "received", responsibleEmployee, "تأكيد استلام العينة", requireReceipt = requireReceipt, occurredAt = at)
                                        }
                                        message = if (changed) "تم توثيق استلام العينة بواسطة $responsibleEmployee." else "لم يُحفظ الانتقال؛ حدّث القائمة وتأكد من الحالة واسم الموظف."
                                    }
                                }) { Text("تأكيد الاستلام") }
                            }
                            if (specimen.status == "received" || (specimen.status == "collected" && !requireReceipt)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val at = System.currentTimeMillis()
                                        val changed = withContext(Dispatchers.IO) {
                                            dao.transitionLabSpecimen(specimen.id, specimen.status, "processing", responsibleEmployee, "بدء المعالجة", requireReceipt = requireReceipt, occurredAt = at)
                                        }
                                        message = if (changed) "تم نقل العينة إلى المعالجة بواسطة $responsibleEmployee." else "تعذر بدء المعالجة؛ تحقق من الحالة واسم الموظف."
                                    }
                                }) { Text("بدء المعالجة") }
                            }
                            if (specimen.status == "processing") {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val at = System.currentTimeMillis()
                                        val changed = withContext(Dispatchers.IO) {
                                            dao.transitionLabSpecimen(specimen.id, specimen.status, "completed", responsibleEmployee, "اكتملت معالجة العينة", requireReceipt = requireReceipt, occurredAt = at)
                                        }
                                        message = if (changed) "تم إكمال معالجة العينة بواسطة $responsibleEmployee." else "تعذر إكمال العينة؛ تحقق من الحالة واسم الموظف."
                                    }
                                }) { Text("إكمال") }
                            }
                            TextButton(onClick = { rejectionReason = ""; message = "اكتب سبب الرفض في الحقل أدناه ثم أكد الرفض للعينة ${specimen.specimenCode}." }) { Text("رفض العينة") }
                        }
                        if (message?.contains(specimen.specimenCode) == true) {
                            OutlinedTextField(
                                value = rejectionReason,
                                onValueChange = { rejectionReason = it },
                                label = { Text("سبب الرفض") },
                                supportingText = { Text("المقترحات: $rejectionReasons") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                enabled = rejectionReason.isNotBlank() && responsibleEmployee.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        val at = System.currentTimeMillis()
                                        val changed = withContext(Dispatchers.IO) {
                                            dao.transitionLabSpecimen(specimen.id, specimen.status, "rejected", responsibleEmployee, rejectionReason.trim(), rejectionReason.trim(), requireReceipt, at)
                                        }
                                        rejectionReason = ""
                                        message = if (changed) "تم توثيق رفض العينة وسببه بواسطة $responsibleEmployee." else "تعذر رفض العينة؛ تحقق من الحالة واسم الموظف."
                                    }
                                }
                            ) { Text("تأكيد رفض العينة") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
