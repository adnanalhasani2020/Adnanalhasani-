package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

private val masterIo = Executors.newSingleThreadExecutor()
private val masterMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MasterDataScreen(context: Context, onBack: () -> Unit) {
    val options = listOf(
        "المستودعات" to "commercial_warehouses",
        "مجموعات الأصناف" to "commercial_product_categories",
        "الأقسام" to "commercial_departments"
    )
    var selected by remember { mutableStateOf(options.first()) }
    var rows by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var warehouseType by remember { mutableStateOf("main") }
    var parentId by remember { mutableStateOf("") }
    var parentMenuExpanded by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        error = ""
        masterIo.execute {
            val result = runCatching { loadCommercialMasterData(context, selected.second) }
            masterMain.post {
                loading = false
                result.onSuccess { rows = it; error = "" }
                    .onFailure { error = it.message ?: "تعذر تحميل البيانات." }
            }
        }
    }
    LaunchedEffect(selected) { parentId = ""; reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المستودعات والمجموعات والأقسام", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = { reload() }, enabled = !loading) { Icon(Icons.Default.Refresh, "تحديث") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { code = ""; name = ""; showCreate = true }, containerColor = Color(0xFF167D63)) {
                Icon(Icons.Default.Add, "إضافة", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("بيانات محفوظة في المؤسسة السحابية، وليست بيانات محلية.", color = Color(0xFF71817B), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { selected = option },
                        label = { Text(option.first) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("بحث بالاسم أو الرمز أو النوع") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text("عدد السجلات: ${rows.size}", color = Color(0xFF71817B), fontSize = 12.sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp))
            if (notice.isNotBlank()) Text(notice, color = Color(0xFF167D63), modifier = Modifier.padding(vertical = 8.dp))
            val filteredRows = rows.filter { row ->
                search.isBlank() || row.label.contains(search.trim(), ignoreCase = true) ||
                    row.code.contains(search.trim(), ignoreCase = true) ||
                    row.kind.contains(search.trim(), ignoreCase = true)
            }
            if (!loading && error.isBlank() && rows.isEmpty()) {
                Text("لا توجد سجلات بعد. استخدم زر الإضافة لإنشاء أول سجل.", modifier = Modifier.padding(top = 24.dp))
            } else if (!loading && error.isBlank() && filteredRows.isEmpty()) {
                Text("لا توجد نتائج تطابق البحث.", modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRows, key = { it.id }) { row ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE5EBE8)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(row.label, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text("الرمز: ${row.code}", color = Color(0xFF71817B), fontSize = 12.sp)
                            if (row.kind.isNotBlank()) Text("النوع: ${row.kind}", color = Color(0xFF71817B), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("إضافة إلى ${selected.first}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("الرمز") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true)
                    if (selected.second != "commercial_warehouses" && rows.isNotEmpty()) {
                        val parentLabel = rows.firstOrNull { it.id == parentId }?.let { "${it.code} — ${it.label}" } ?: "جذر رئيسي (بلا أب)"
                        Box {
                            OutlinedButton(onClick = { parentMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("المستوى الأعلى: $parentLabel") }
                            DropdownMenu(expanded = parentMenuExpanded, onDismissRequest = { parentMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("جذر رئيسي (بلا أب)") }, onClick = { parentId = ""; parentMenuExpanded = false })
                                rows.forEach { parent -> DropdownMenuItem(text = { Text("${parent.code} — ${parent.label}") }, onClick = { parentId = parent.id; parentMenuExpanded = false }) }
                            }
                        }
                    }
                    if (selected.second == "commercial_warehouses") {
                        Text("نوع المستودع")
                        Column {
                            listOf("main" to "رئيسي", "retail" to "تجزئة", "distribution" to "توزيع", "returns" to "مرتجعات", "quarantine" to "حجر", "virtual" to "افتراضي").forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = warehouseType == item.first, onClick = { warehouseType = item.first })
                                    Text(item.second)
                                }
                            }
                        }
                    } else {
                        Text("يمكن بناء مستويات فرعية متعددة بلا حد ثابت عبر اختيار المستوى الأعلى.", fontSize = 12.sp, color = Color(0xFF71817B))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    notice = ""
                    masterIo.execute {
                        val result = runCatching {
                            createCommercialMasterData(context, selected.second, code, name, parentId = parentId, warehouseType = warehouseType)
                            loadCommercialMasterData(context, selected.second)
                        }
                        masterMain.post {
                            result.onSuccess { rows = it; notice = "تم الحفظ في قاعدة البيانات السحابية."; error = "" }
                                .onFailure { error = it.message ?: "تعذر الحفظ."; notice = "" }
                        }
                    }
                }, enabled = code.isNotBlank() && name.isNotBlank()) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("إلغاء") } }
        )
    }
}
