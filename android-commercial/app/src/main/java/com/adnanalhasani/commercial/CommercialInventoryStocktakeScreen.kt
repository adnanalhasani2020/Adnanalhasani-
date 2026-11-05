package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.util.concurrent.Executors

private val stocktakeIo = Executors.newSingleThreadExecutor()
private val stocktakeMain = Handler(Looper.getMainLooper())
private val stocktakeInk = Color(0xFF183B35)
private val stocktakeMuted = Color(0xFF71817B)
private val stocktakeCanvas = Color(0xFFF5F7F6)

private data class StocktakeScreenSnapshot(
    val sessions: List<CommercialInventoryStocktake>,
    val selected: CommercialInventoryStocktake?,
    val lines: List<CommercialInventoryStocktakeLine>
)

private fun stocktakeStatusLabel(status: String): String = when (status) {
    "draft" -> "مسودة"
    "submitted" -> "بانتظار الاعتماد"
    "approved" -> "معتمد — بانتظار الترحيل"
    "posted" -> "مُرحّل"
    "reversed" -> "معكوس"
    else -> "حالة غير معروفة"
}

private fun stocktakeNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialInventoryStocktakeScreen(context: Context, onBack: () -> Unit) {
    var sessions by remember { mutableStateOf<List<CommercialInventoryStocktake>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CommercialInventoryStocktake?>(null) }
    var lines by remember { mutableStateOf<List<CommercialInventoryStocktakeLine>>(emptyList()) }
    var notes by remember { mutableStateOf("") }
    var reverseReason by remember { mutableStateOf("") }
    var showReverseDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var schemaReady by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }

    fun loadSnapshot(preferredId: String?): StocktakeScreenSnapshot {
        val loaded = loadCommercialInventoryStocktakes(context)
        val chosen = loaded.firstOrNull { it.id == preferredId }
            ?: loaded.firstOrNull { it.status != "reversed" }
            ?: loaded.firstOrNull()
        val detail = chosen?.let { loadCommercialInventoryStocktakeLines(context, it.id) }.orEmpty()
        return StocktakeScreenSnapshot(loaded, chosen, detail)
    }

    fun reload(preferredId: String? = selectedId.takeIf { it.isNotBlank() }) {
        loading = true
        error = ""
        stocktakeIo.execute {
            val result = runCatching { loadSnapshot(preferredId) }
            stocktakeMain.post {
                loading = false
                result.onSuccess {
                    schemaReady = true
                    sessions = it.sessions
                    selected = it.selected
                    selectedId = it.selected?.id.orEmpty()
                    lines = it.lines
                    if (it.selected == null) notice = "لا توجد جلسات جرد لهذا الفرع بعد."
                }.onFailure {
                    schemaReady = false
                    error = it.message ?: "تعذر تحميل مستندات الجرد."
                }
            }
        }
    }

    fun performAction(successMessage: String, action: () -> String? = { null }) {
        if (saving || !schemaReady) return
        saving = true
        error = ""
        notice = ""
        val preferred = selectedId
        stocktakeIo.execute {
            val actionResult = runCatching { action() }
            val preferredAfter = actionResult.getOrNull()?.takeIf { it.isNotBlank() } ?: preferred
            val snapshotResult = runCatching { loadSnapshot(preferredAfter) }
            stocktakeMain.post {
                saving = false
                actionResult.onSuccess { notice = successMessage }
                    .onFailure { error = it.message ?: "تعذر تنفيذ العملية." }
                snapshotResult.onSuccess {
                    schemaReady = true
                    sessions = it.sessions
                    selected = it.selected
                    selectedId = it.selected?.id.orEmpty()
                    lines = it.lines
                }.onFailure {
                    schemaReady = false
                    if (error.isBlank()) error = it.message ?: "تعذر تحديث عرض الجرد."
                }
            }
        }
    }

    LaunchedEffect(reloadToken) { reload() }

    val countedLines = lines.count { it.countedQuantity.isNotBlank() }
    val varianceLines = lines.count { line ->
        val counted = line.countedQuantity.toBigDecimalOrNull()
        counted != null && counted.compareTo(line.expectedQuantity) != 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("الجرد والتسويات المخزنية", color = stocktakeInk, fontWeight = FontWeight.Bold)
                        Text("لقطة رصيد · اعتماد مستقل · ترحيل وعكس موثق", color = stocktakeMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = { IconButton(onClick = { reloadToken++ }, enabled = !loading && !saving) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = stocktakeCanvas)
            )
        },
        containerColor = stocktakeCanvas
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StocktakeMetric("الجلسات", sessions.size.toString(), Modifier.weight(1f))
                    StocktakeMetric("بنود الفرع", lines.size.toString(), Modifier.weight(1f))
                    StocktakeMetric("تم عدّها", "$countedLines/${lines.size}", Modifier.weight(1f))
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE4EBE7))
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("بدء جلسة جرد جديدة", color = stocktakeInk, fontWeight = FontWeight.Bold)
                        Text("هذه جلسة جرد على مستوى الفرع بالكامل وبالوحدة الأساسية للمخزون، وليست جردًا لمستودع منفرد أو لمواقع التخزين الفرعية. يحفظ الخادم لقطة الرصيد ولا يغير المخزون عند إنشاء المسودة.", color = stocktakeMuted, fontSize = 11.sp)
                        OutlinedTextField(
                            value = notes, onValueChange = { notes = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("ملاحظة الجرد (اختياري)") },
                            minLines = 1, maxLines = 3
                        )
                        Button(
                            onClick = {
                                val noteSnapshot = notes
                                performAction("تم إنشاء مسودة الجرد.") {
                                    createCommercialInventoryStocktake(context, noteSnapshot)
                                }
                            },
                            enabled = schemaReady && !loading && !saving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("إنشاء مسودة جرد")
                        }
                    }
                }
            }
            if (sessions.isNotEmpty()) {
                item { Text("جلسات الجرد السابقة", color = stocktakeInk, fontWeight = FontWeight.Bold) }
                items(sessions, key = { it.id }) { session ->
                    Card(
                        onClick = { if (schemaReady && !saving && selectedId != session.id) reload(session.id) },
                        colors = CardDefaults.cardColors(containerColor = if (selectedId == session.id) Color(0xFFEAF3EF) else Color.White),
                        border = BorderStroke(1.dp, if (selectedId == session.id) Color(0xFF9CC8B8) else Color(0xFFE4EBE7)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stocktakeStatusLabel(session.status), color = stocktakeInk, fontWeight = FontWeight.Bold)
                            if (session.notes.isNotBlank()) Text(session.notes, color = stocktakeMuted, fontSize = 11.sp)
                            Text(session.createdAt.replace("T", " ").take(16), color = stocktakeMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
            selected?.let { active ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFE4EBE7))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("تفاصيل الجلسة المحددة", color = stocktakeInk, fontWeight = FontWeight.Bold)
                            Text(stocktakeStatusLabel(active.status), color = stocktakeInk, fontSize = 12.sp)
                            Text("البنود التي لها فرق: $varianceLines", color = stocktakeMuted, fontSize = 11.sp)
                            when (active.status) {
                                "draft" -> {
                                    Text("أدخل الكمية الفعلية لكل صنف. لا يمكن إرسال الجرد حتى تُستكمل جميع البنود.", color = stocktakeMuted, fontSize = 11.sp)
                                    Button(
                                        onClick = {
                                            val linesSnapshot = lines.toList()
                                            performAction("تم حفظ الكميات الفعلية.") {
                                                saveCommercialInventoryStocktakeCounts(context, active.id, linesSnapshot)
                                                active.id
                                            }
                                        },
                                        enabled = schemaReady && !saving && !loading && countedLines > 0,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("حفظ العدّ الفعلي") }
                                    Button(
                                        onClick = {
                                            val linesSnapshot = lines.toList()
                                            performAction("تم إرسال الجرد للمراجعة.") {
                                                saveCommercialInventoryStocktakeCounts(context, active.id, linesSnapshot)
                                                submitCommercialInventoryStocktake(context, active.id)
                                                active.id
                                            }
                                        },
                                        enabled = schemaReady && !saving && !loading && lines.isNotEmpty() && countedLines == lines.size,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("حفظ وإرسال للاعتماد")
                                    }
                                }
                                "submitted" -> {
                                    Text("يجب أن يعتمد الجرد مستخدم آخر غير منشئ الجلسة ومرسل العد.", color = stocktakeMuted, fontSize = 11.sp)
                                    Button(
                                        onClick = { performAction("تم اعتماد الجرد.") { approveCommercialInventoryStocktake(context, active.id); active.id } },
                                        enabled = schemaReady && !saving && !loading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("اعتماد الجرد")
                                    }
                                }
                                "approved" -> {
                                    Text("تم اعتماد العد، لكن الترحيل معطل حتى يكتمل ربط قيد فروقات المخزون بحسابات الأستاذ العام. لا تغيّر هذه الشاشة الأرصدة الآن.", color = Color(0xFFB45309), fontSize = 11.sp)
                                    Button(
                                        onClick = {},
                                        enabled = false,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("الترحيل معطل حتى اكتمال القيود المحاسبية") }
                                }
                                "posted" -> {
                                    Text("العكس معطل حتى يتوفر القيد المحاسبي العكسي المرتبط بالقيد الأصلي. لا يمكن عكس الكمية وحدها.", color = Color(0xFFB45309), fontSize = 11.sp)
                                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Undo, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("العكس معطل حتى اكتمال القيود المحاسبية")
                                    }
                                }
                                "reversed" -> Text("هذا المستند معكوس بحركة مقابلة؛ لا يمكن تعديله أو ترحيله مرة أخرى.", color = stocktakeMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (active.status == "draft") {
                    item { Text("بنود الجرد", color = stocktakeInk, fontWeight = FontWeight.Bold) }
                    items(lines, key = { it.id }) { line ->
                        StocktakeLineCard(
                            line = line,
                            editable = !saving,
                            onCountChange = { value -> lines = lines.map { if (it.id == line.id) it.copy(countedQuantity = value) else it } },
                            onCostChange = { value -> lines = lines.map { if (it.id == line.id) it.copy(adjustmentUnitCostMinor = value) else it } }
                        )
                    }
                } else {
                    item { Text("ملخص البنود", color = stocktakeInk, fontWeight = FontWeight.Bold) }
                    items(lines, key = { it.id }) { line ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp, Color(0xFFE4EBE7))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.productName, color = stocktakeInk, fontWeight = FontWeight.SemiBold)
                                Text("الدفتري (الوحدة الأساسية): ${stocktakeNumber(line.expectedQuantity)} ${line.unitName}", color = stocktakeMuted, fontSize = 11.sp)
                                Text("الفعلي (الوحدة الأساسية): ${line.countedQuantity.ifBlank { "—" }} ${line.unitName}", color = stocktakeInk, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (loading || saving) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(if (saving) "جارٍ تنفيذ العملية والتحقق من النتيجة..." else "جارٍ تحميل الجرد...", color = stocktakeMuted, fontSize = 12.sp)
                    }
                }
            }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (notice.isNotBlank()) item { Text(notice, color = Color(0xFF167D63), fontSize = 12.sp) }
            if (selected == null && sessions.isEmpty() && !loading) {
                item { Text("لا توجد جلسات جرد محفوظة لهذا الفرع.", color = stocktakeMuted) }
            }
        }
    }

    if (showReverseDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showReverseDialog = false },
            title = { Text("عكس مستند الجرد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سينشئ النظام حركات عكسية ويعيد طبقات التكلفة. لن يسمح بالعكس إذا وُجدت حركة لاحقة.", color = stocktakeMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = reverseReason, onValueChange = { reverseReason = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("سبب العكس") },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val reason = reverseReason
                        val activeId = selectedId
                        showReverseDialog = false
                        performAction("تم عكس مستند الجرد.") {
                            reverseCommercialInventoryStocktake(context, activeId, reason)
                            activeId
                        }
                    },
                    enabled = !saving && reverseReason.trim().length >= 8
                ) { Text("تأكيد العكس") }
            },
            dismissButton = { TextButton(onClick = { showReverseDialog = false }, enabled = !saving) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun StocktakeMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = stocktakeMuted, fontSize = 10.sp)
            Text(value, color = stocktakeInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun StocktakeLineCard(
    line: CommercialInventoryStocktakeLine,
    editable: Boolean,
    onCountChange: (String) -> Unit,
    onCostChange: (String) -> Unit
) {
    val counted = line.countedQuantity.toBigDecimalOrNull()
    val difference = counted?.subtract(line.expectedQuantity)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE4EBE7))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(line.productName, color = stocktakeInk, fontWeight = FontWeight.Bold)
            Text("الرصيد الدفتري (الوحدة الأساسية): ${stocktakeNumber(line.expectedQuantity)} ${line.unitName}", color = stocktakeMuted, fontSize = 11.sp)
            OutlinedTextField(
                value = line.countedQuantity,
                onValueChange = onCountChange,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("الكمية الفعلية (بالوحدة الأساسية للمخزون)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            difference?.let {
                Text(
                    "الفرق: ${stocktakeNumber(it)} ${line.unitName}",
                    color = if (it.signum() == 0) stocktakeMuted else if (it.signum() > 0) Color(0xFF167D63) else Color(0xFFB45309),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (it.signum() > 0) {
                    OutlinedTextField(
                        value = line.adjustmentUnitCostMinor,
                        onValueChange = onCostChange,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("تكلفة الوحدة للزيادة (أصغر وحدة نقدية)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    }
}
