package com.adnanalhasani.commercial

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

private val crmScreenIo = Executors.newSingleThreadExecutor()
private val crmScreenMain = Handler(Looper.getMainLooper())

private fun chooseCrmDateTime(context: Context, onSelected: (String) -> Unit) {
    val calendar = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, day ->
            calendar.set(Calendar.YEAR, year)
            calendar.set(Calendar.MONTH, month)
            calendar.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    onSelected(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(calendar.time))
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun crmDateLabel(value: String): String =
    value.replace('T', ' ').take(16).ifBlank { "غير محدد" }

private fun crmStatusLabel(value: String): String = when (value) {
    "new" -> "جديد"
    "contacted" -> "تم التواصل"
    "qualified" -> "مؤهل"
    "unqualified" -> "غير مؤهل"
    "lost" -> "مفقود"
    "open" -> "مفتوحة"
    "completed" -> "مكتملة"
    "cancelled" -> "ملغاة"
    else -> value
}

private fun crmSourceLabel(value: String): String = when (value) {
    "walk_in" -> "زيارة مباشرة"
    "referral" -> "إحالة"
    "social" -> "شبكات التواصل"
    "website" -> "الموقع"
    "campaign" -> "حملة"
    "phone" -> "اتصال"
    else -> "أخرى"
}

private fun crmActivityTypeLabel(value: String): String = when (value) {
    "call" -> "اتصال"
    "meeting" -> "اجتماع"
    "email" -> "بريد"
    "task" -> "مهمة"
    else -> "ملاحظة"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCrmScreen(context: Context, onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var leads by remember { mutableStateOf<List<CommercialCrmLead>>(emptyList()) }
    var activities by remember { mutableStateOf<List<CommercialCrmActivity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showLeadDialog by remember { mutableStateOf(false) }
    var savingLead by remember { mutableStateOf(false) }
    var savingActivity by remember { mutableStateOf(false) }
    var followUpLead by remember { mutableStateOf<CommercialCrmLead?>(null) }
    var pendingLeadKey by remember { mutableStateOf(newCommercialCrmIdempotencyKey()) }
    var pendingActivityKey by remember { mutableStateOf(newCommercialCrmIdempotencyKey()) }

    LaunchedEffect(refreshToken) {
        loading = true
        error = ""
        crmScreenIo.execute {
            val result = runCatching {
                loadCommercialCrmLeads(context) to loadCommercialCrmActivities(context)
            }
            crmScreenMain.post {
                result.onSuccess { (loadedLeads, loadedActivities) ->
                    leads = loadedLeads
                    activities = loadedActivities
                }.onFailure { error = it.message ?: "تعذر تحميل بيانات إدارة العملاء." }
                loading = false
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F7F6),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("إدارة العملاء المحتملين", fontWeight = FontWeight.Bold)
                        Text("الفرص الأولية وسجل المتابعات", fontSize = 11.sp, color = Color(0xFF71817B))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = {
                    IconButton(onClick = { refreshToken++ }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F7F6))
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showLeadDialog = true }, containerColor = Color(0xFF167D63), contentColor = Color.White) {
                Icon(Icons.Default.Add, contentDescription = "إضافة عميل محتمل")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, containerColor = Color.White) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("العملاء المحتملون") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("المتابعات") })
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (error.isNotBlank()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1EF))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("تعذر تحميل بيانات CRM", color = Color(0xFF9C3028), fontWeight = FontWeight.Bold)
                                Text(error, color = Color(0xFF7B3933), fontSize = 12.sp)
                                OutlinedButton(onClick = { refreshToken++ }) { Text("إعادة المحاولة") }
                            }
                        }
                    }
                    if (selectedTab == 0) {
                        item {
                            CrmSummaryCard(
                                title = "سجل العملاء المحتملين",
                                count = leads.size,
                                detail = "${leads.count { it.status == "new" }} جديد • ${leads.count { it.nextFollowUpAt.isNotBlank() }} لديهم متابعة"
                            )
                        }
                        if (leads.isEmpty() && error.isBlank()) item { CrmEmptyState("لا توجد سجلات بعد", "أضف أول عميل محتمل وابدأ تسجيل التواصل والمتابعة.") }
                        items(leads, key = { it.id }) { lead ->
                            CrmLeadCard(
                                lead = lead,
                                onFollowUp = { followUpLead = lead },
                                onStatus = { status ->
                                    crmScreenIo.execute {
                                        val result = runCatching {
                                            updateCommercialCrmLead(context, lead, status, lead.notes, lead.nextFollowUpAt.ifBlank { null })
                                        }
                                        crmScreenMain.post {
                                            result.onSuccess { refreshToken++ }
                                                .onFailure { error = it.message ?: "تعذر تحديث حالة العميل." }
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        item {
                            CrmSummaryCard(
                                title = "سجل المتابعات والمهام",
                                count = activities.size,
                                detail = "${activities.count { it.status == "open" }} مفتوحة • ${activities.count { it.status == "completed" }} مكتملة"
                            )
                        }
                        if (activities.isEmpty() && error.isBlank()) item { CrmEmptyState("لا توجد متابعات بعد", "افتح سجل عميل محتمل وأضف اتصالًا أو اجتماعًا أو مهمة.") }
                        items(activities, key = { it.id }) { activity ->
                            CrmActivityCard(activity = activity, leadName = leads.firstOrNull { it.id == activity.leadId }?.name.orEmpty()) {
                                crmScreenIo.execute {
                                    val result = runCatching {
                                        updateCommercialCrmActivity(context, activity, "completed", activity.description)
                                    }
                                    crmScreenMain.post {
                                        result.onSuccess { refreshToken++ }
                                            .onFailure { error = it.message ?: "تعذر إكمال المتابعة." }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }

    if (showLeadDialog) {
        CrmLeadDialog(
            onDismiss = { showLeadDialog = false },
            saving = savingLead,
            onSave = { name, company, email, phone, source, notes, nextFollowUp ->
                savingLead = true
                crmScreenIo.execute {
                    val result = runCatching {
                        createCommercialCrmLead(context, name, company, email, phone, source, notes, nextFollowUp, pendingLeadKey)
                    }
                    crmScreenMain.post {
                        result.onSuccess {
                            savingLead = false
                            pendingLeadKey = newCommercialCrmIdempotencyKey()
                            showLeadDialog = false
                            refreshToken++
                        }.onFailure { savingLead = false; error = it.message ?: "تعذر حفظ العميل المحتمل." }
                    }
                }
            }
        )
    }

    followUpLead?.let { lead ->
        CrmFollowUpDialog(
            leadName = lead.name,
            onDismiss = { followUpLead = null },
            saving = savingActivity,
            onSave = { type, title, description, dueAt ->
                savingActivity = true
                crmScreenIo.execute {
                    val result = runCatching {
                        createCommercialCrmActivity(context, lead.id, type, title, description, dueAt, pendingActivityKey)
                    }
                    crmScreenMain.post {
                        result.onSuccess {
                            savingActivity = false
                            pendingActivityKey = newCommercialCrmIdempotencyKey()
                            followUpLead = null
                            selectedTab = 1
                            refreshToken++
                        }.onFailure { savingActivity = false; error = it.message ?: "تعذر حفظ المتابعة." }
                    }
                }
            }
        )
    }
}

@Composable
private fun CrmSummaryCard(title: String, count: Int, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF183B35)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color(0xFFBFD9D0), fontSize = 12.sp)
            Text(count.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Color(0xFF8DE0B8), fontSize = 12.sp)
        }
    }
}

@Composable
private fun CrmEmptyState(title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5EBE8)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color(0xFF183B35), fontWeight = FontWeight.Bold)
            Text(detail, color = Color(0xFF71817B), fontSize = 12.sp)
        }
    }
}

@Composable
private fun CrmLeadCard(lead: CommercialCrmLead, onFollowUp: () -> Unit, onStatus: (String) -> Unit) {
    var menuExpanded by remember(lead.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE5EBE8)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(lead.name, color = Color(0xFF183B35), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (lead.companyName.isNotBlank()) Text(lead.companyName, color = Color(0xFF71817B), fontSize = 12.sp)
                }
                Box {
                    TextButton(onClick = { menuExpanded = true }) { Text(crmStatusLabel(lead.status)) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        listOf("new", "contacted", "qualified", "unqualified", "lost").forEach { status ->
                            DropdownMenuItem(text = { Text(crmStatusLabel(status)) }, onClick = {
                                menuExpanded = false
                                onStatus(status)
                            })
                        }
                    }
                }
            }
            if (lead.phone.isNotBlank()) Text("الهاتف: ${lead.phone}", color = Color(0xFF52665F), fontSize = 12.sp)
            if (lead.email.isNotBlank()) Text("البريد: ${lead.email}", color = Color(0xFF52665F), fontSize = 12.sp)
            Text("المصدر: ${crmSourceLabel(lead.source)}", color = Color(0xFF71817B), fontSize = 11.sp)
            if (lead.nextFollowUpAt.isNotBlank()) Text("المتابعة القادمة: ${crmDateLabel(lead.nextFollowUpAt)}", color = Color(0xFF167D63), fontSize = 12.sp)
            if (lead.notes.isNotBlank()) Text(lead.notes, color = Color(0xFF52665F), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onFollowUp) { Text("إضافة متابعة") }
            }
        }
    }
}

@Composable
private fun CrmActivityCard(activity: CommercialCrmActivity, leadName: String, onComplete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE5EBE8)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(activity.title, color = Color(0xFF183B35), fontWeight = FontWeight.Bold)
                    if (leadName.isNotBlank()) Text(leadName, color = Color(0xFF71817B), fontSize = 12.sp)
                }
                Text(crmStatusLabel(activity.status), color = if (activity.status == "completed") Color(0xFF167D63) else Color(0xFF9A6816), fontSize = 11.sp)
            }
            Text("${crmActivityTypeLabel(activity.activityType)} • ${crmDateLabel(activity.dueAt)}", color = Color(0xFF52665F), fontSize = 12.sp)
            if (activity.description.isNotBlank()) Text(activity.description, color = Color(0xFF71817B), fontSize = 12.sp)
            if (activity.status == "open") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onComplete) { Text("إكمال المتابعة") }
                }
            }
        }
    }
}

@Composable
private fun CrmLeadDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("other") }
    var notes by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf("") }
    var sourceExpanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("إضافة عميل محتمل") },
        text = {
            Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم العميل *") }, singleLine = true)
                OutlinedTextField(company, { company = it }, label = { Text("الشركة") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { sourceExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("المصدر: ${crmSourceLabel(source)}")
                    }
                    DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                        listOf("walk_in", "referral", "social", "website", "campaign", "phone", "other").forEach { value ->
                            DropdownMenuItem(text = { Text(crmSourceLabel(value)) }, onClick = { source = value; sourceExpanded = false })
                        }
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات") }, minLines = 2)
                OutlinedButton(onClick = {
                    chooseCrmDateTime(context, { followUp = it })
                }, modifier = Modifier.fillMaxWidth()) { Text(if (followUp.isBlank()) "تحديد موعد متابعة" else crmDateLabel(followUp)) }
                Text("يمكنك إضافة الموعد لاحقًا من سجل العميل.", color = Color(0xFF71817B), fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank() && !saving) { onSave(name, company, email, phone, source, notes, followUp.ifBlank { null }) } }, enabled = name.isNotBlank() && !saving) {
                Text(if (saving) "جارٍ الحفظ…" else "حفظ العميل")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("إلغاء") } }
    )
}

@Composable
private fun CrmFollowUpDialog(
    leadName: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?) -> Unit
) {
    var type by remember { mutableStateOf("call") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dueAt by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("متابعة: $leadName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("النوع: ${crmActivityTypeLabel(type)}")
                    }
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        listOf("call", "meeting", "email", "task", "note").forEach { value ->
                            DropdownMenuItem(text = { Text(crmActivityTypeLabel(value)) }, onClick = { type = value; typeExpanded = false })
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("عنوان المتابعة *") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("التفاصيل") }, minLines = 2)
                OutlinedButton(onClick = { chooseCrmDateTime(context) { dueAt = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (dueAt.isBlank()) "تحديد موعد (اختياري)" else crmDateLabel(dueAt))
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank() && !saving) { onSave(type, title, description, dueAt.ifBlank { null }) } }, enabled = title.isNotBlank() && !saving) {
                Text(if (saving) "جارٍ الحفظ…" else "حفظ المتابعة")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("إلغاء") } }
    )
}

@Composable
private fun onSaveContext(): Context = androidx.compose.ui.platform.LocalContext.current
