package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun specimenStatusLabel(status: String): String = when (status) {
    "collected" -> "تم جمعها"
    "received" -> "تم الاستلام"
    "processing" -> "قيد المعالجة"
    "accepted" -> "مقبولة"
    "rejected" -> "مرفوضة"
    else -> status
}

@Composable
fun LabSpecimenScreen(
    repository: HospitalRepository,
    onSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val orders by repository.labOrders().collectAsState(initial = emptyList())
    val specimens by repository.labSpecimens().collectAsState(initial = emptyList())
    val types by repository.referenceItems("lab_specimen_types").collectAsState(initial = emptyList())
    val rejectionReasons by repository.referenceItems("lab_specimen_rejection_reasons").collectAsState(initial = emptyList())
    var selectedOrderId by remember(orders) { mutableStateOf(orders.firstOrNull()?.id) }
    var selectedType by remember(types) { mutableStateOf(types.firstOrNull { it.active }?.name.orEmpty()) }
    var specimenCode by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var transitionNote by remember { mutableStateOf("") }
    var selectedReason by remember(rejectionReasons) { mutableStateOf(rejectionReasons.firstOrNull { it.active }?.name.orEmpty()) }
    var orderMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var reasonMenu by remember { mutableStateOf(false) }
    var expandedHistoryId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val activeTypes = types.filter { it.active }.map { it.name }
    val activeReasons = rejectionReasons.filter { it.active }.map { it.name }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Science, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("تتبّع عينات المختبر", style = MaterialTheme.typography.titleLarge)
                    Text("سجل مستقل للعينة ومراحلها محفوظ محلياً في SQLite.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "إعدادات المختبر")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("تسجيل عينة جديدة", style = MaterialTheme.typography.titleMedium)
                    if (orders.isEmpty()) {
                        Text("لا توجد طلبات فحص بعد. أضف طلباً من شاشة المختبر أولاً.", color = MaterialTheme.colorScheme.error)
                    } else {
                        BoxMenuField(
                            label = "طلب الفحص",
                            value = orders.firstOrNull { it.id == selectedOrderId }?.let { "#${it.id} • ${it.patientName} • ${it.testName}" } ?: "اختر طلباً",
                            expanded = orderMenu,
                            onExpandedChange = { orderMenu = it },
                            options = orders.map { it.id to "#${it.id} • ${it.patientName} • ${it.testName}" },
                            onSelect = { selectedOrderId = it; orderMenu = false }
                        )
                    }
                    OutlinedTextField(
                        value = specimenCode, onValueChange = { specimenCode = it; message = null },
                        label = { Text("رمز العينة الفريد") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    BoxMenuField(
                        label = "نوع العينة (من إعدادات المدير)",
                        value = selectedType.ifBlank { "اختر نوع العينة" },
                        expanded = typeMenu, onExpandedChange = { typeMenu = it },
                        options = activeTypes.mapIndexed { i, value -> i.toLong() to value },
                        onSelect = { selectedType = activeTypes.getOrNull(it.toInt()).orEmpty(); typeMenu = false }
                    )
                    OutlinedTextField(
                        value = actor, onValueChange = { actor = it; message = null },
                        label = { Text("اسم جامع/مستلم العينة") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it },
                        label = { Text("ملاحظات العينة (اختياري)") }, modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = orders.isNotEmpty() && selectedOrderId != null && specimenCode.isNotBlank() && selectedType.isNotBlank() && actor.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching {
                                    repository.createLabSpecimen(
                                        labOrderId = selectedOrderId!!,
                                        specimenCode = specimenCode,
                                        specimenType = selectedType,
                                        actor = actor,
                                        notes = notes
                                    )
                                }.onSuccess {
                                    specimenCode = ""
                                    notes = ""
                                    message = "تم حفظ العينة وسجل أول حدث في SQLite."
                                }.onFailure {
                                    message = "تعذر الحفظ. تحقق من عدم تكرار رمز العينة واكتمال البيانات."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("حفظ العينة") }
                    if (activeTypes.isEmpty()) {
                        Text("أضف أنواع العينات من إعدادات المختبر قبل التسجيل.", color = MaterialTheme.colorScheme.error)
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Text("سجل العينات", style = MaterialTheme.typography.titleLarge)
            Text("المسار المسموح: جمع ← استلام ← معالجة ← قبول، ويمكن رفض العينة مع تسجيل السبب.", style = MaterialTheme.typography.bodySmall)
        }
        if (specimens.isEmpty()) {
            item { Text("لا توجد عينات مسجلة حتى الآن.") }
        }
        items(specimens, key = { it.id }) { specimen ->
            val order = orders.firstOrNull { it.id == specimen.labOrderId }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${specimen.specimenCode} • ${specimen.specimenType}", style = MaterialTheme.typography.titleMedium)
                    Text("طلب #${specimen.labOrderId}: ${order?.patientName ?: "طلب غير متاح"} • ${order?.testName.orEmpty()}")
                    Text("الحالة: ${specimenStatusLabel(specimen.status)}")
                    if (specimen.receivedBy.isNotBlank()) Text("استلمها: ${specimen.receivedBy}")
                    if (specimen.rejectionReason.isNotBlank()) Text("سبب الرفض: ${specimen.rejectionReason}")
                    if (specimen.notes.isNotBlank()) Text("ملاحظات: ${specimen.notes}")
                    OutlinedTextField(
                        value = transitionNote, onValueChange = { transitionNote = it },
                        label = { Text("ملاحظة الانتقال / سبب الرفض") }, modifier = Modifier.fillMaxWidth()
                    )
                    val next = when (specimen.status) {
                        "collected" -> "received"
                        "received" -> "processing"
                        "processing" -> "accepted"
                        else -> null
                    }
                    if (next != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val ok = repository.transitionLabSpecimen(specimen.id, next, actor, transitionNote)
                                    message = if (ok) "تم تحديث حالة العينة." else "أدخل اسم الموظف، وللرفض سبباً واضحاً."
                                    if (ok) transitionNote = ""
                                }
                            },
                            enabled = actor.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(when (next) {
                            "received" -> "تأكيد استلام العينة"
                            "processing" -> "بدء المعالجة"
                            else -> "اعتماد قبول العينة"
                        }) }
                        if (specimen.status != "accepted" && specimen.status != "rejected") {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val reason = selectedReason.ifBlank { transitionNote }
                                        val note = listOf(reason, transitionNote).filter { it.isNotBlank() }.distinct().joinToString(" — ")
                                        val ok = repository.transitionLabSpecimen(specimen.id, "rejected", actor, note)
                                        message = if (ok) "تم رفض العينة مع حفظ السبب في سجل الأحداث." else "اختر سبب الرفض وأدخل اسم الموظف."
                                    }
                                },
                                enabled = actor.isNotBlank() && (selectedReason.isNotBlank() || transitionNote.isNotBlank()),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("رفض العينة") }
                            if (activeReasons.isNotEmpty()) {
                                BoxMenuField(
                                    label = "سبب الرفض",
                                    value = selectedReason.ifBlank { "اختر سبباً" },
                                    expanded = reasonMenu, onExpandedChange = { reasonMenu = it },
                                    options = activeReasons.mapIndexed { i, value -> i.toLong() to value },
                                    onSelect = { id -> selectedReason = activeReasons.getOrNull(id.toInt()).orEmpty(); reasonMenu = false }
                                )
                            }
                        }
                    }
                    TextButton(onClick = { expandedHistoryId = if (expandedHistoryId == specimen.id) null else specimen.id }) {
                        Text(if (expandedHistoryId == specimen.id) "إخفاء سجل الحالة" else "عرض سجل الحالة")
                    }
                    if (expandedHistoryId == specimen.id) LabSpecimenHistory(repository, specimen.id)
                }
            }
        }
    }
}

@Composable
private fun BoxMenuField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(value, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                options.forEach { (id, labelText) ->
                    DropdownMenuItem(
                        text = { Text(labelText) },
                        onClick = { onSelect(id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LabSpecimenHistory(repository: HospitalRepository, specimenId: Long) {
    val events by repository.labSpecimenEvents(specimenId).collectAsState(initial = emptyList())
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        events.forEach { event ->
            Text(
                "${specimenStatusLabel(event.toStatus)} • ${event.actor} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(event.occurredAt))}",
                style = MaterialTheme.typography.bodySmall
            )
            if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LabSpecimenSettingsScreen(repository: HospitalRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val types by repository.referenceItems("lab_specimen_types").collectAsState(initial = emptyList())
    val reasons by repository.referenceItems("lab_specimen_rejection_reasons").collectAsState(initial = emptyList())
    var newType by remember { mutableStateOf("") }
    var newReason by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Column {
                    Text("إعدادات المختبر", style = MaterialTheme.typography.titleLarge)
                    Text("تخصيص أنواع العينات وأسباب الرفض في قاعدة SQLite المحلية.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("تُستخدم هذه القوائم في شاشة تتبع العينات. لا تغيّر هذه الإعدادات صلاحيات المستخدمين.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أنواع العينات", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(newType, { newType = it }, label = { Text("نوع جديد") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        scope.launch {
                            val ok = repository.addReference("lab_specimen_types", newType)
                            message = if (ok) "تمت إضافة نوع العينة." else "اكتب اسماً جديداً؛ قد يكون الاسم مستخدماً بالفعل."
                            if (ok) newType = ""
                        }
                    }, enabled = newType.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إضافة نوع") }
                    types.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name + if (item.active) "" else " (غير نشط)", modifier = Modifier.weight(1f))
                            if (item.active) TextButton(onClick = { scope.launch { repository.deactivateReference(item.id) } }) { Text("تعطيل") }
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أسباب رفض العينة", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(newReason, { newReason = it }, label = { Text("سبب جديد") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        scope.launch {
                            val ok = repository.addReference("lab_specimen_rejection_reasons", newReason)
                            message = if (ok) "تمت إضافة سبب الرفض." else "اكتب سبباً جديداً؛ قد يكون مستخدماً بالفعل."
                            if (ok) newReason = ""
                        }
                    }, enabled = newReason.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إضافة سبب") }
                    reasons.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name + if (item.active) "" else " (غير نشط)", modifier = Modifier.weight(1f))
                            if (item.active) TextButton(onClick = { scope.launch { repository.deactivateReference(item.id) } }) { Text("تعطيل") }
                        }
                    }
                }
            }
        }
        item { message?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
