package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

private val unitDirectoryIo = Executors.newSingleThreadExecutor()
private val unitDirectoryMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialUnitDirectoryScreen(context: Context, onBack: () -> Unit) {
    var units by remember { mutableStateOf<List<CommercialUnitOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedDimension by remember { mutableStateOf("all") }
    var refreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshToken) {
        loading = true
        error = ""
        unitDirectoryIo.execute {
            val result = runCatching { loadCommercialUnitsOfMeasure(context) }
            unitDirectoryMain.post {
                result.onSuccess { units = it }
                    .onFailure { error = it.message ?: "تعذر تحميل دليل وحدات القياس." }
                loading = false
            }
        }
    }

    val dimensions = remember(units) { units.map { it.dimension.ifBlank { "غير محدد" } }.distinct().sorted() }
    val filtered = remember(units, query, selectedDimension) {
        units.filter { unit ->
            (selectedDimension == "all" || (unit.dimension.ifBlank { "غير محدد" } == selectedDimension)) &&
                (query.isBlank() || listOf(unit.code, unit.label, unit.dimension).any { it.contains(query.trim(), ignoreCase = true) })
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F7F6),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("دليل وحدات القياس", fontWeight = FontWeight.Bold)
                        Text("الوحدات النشطة المتاحة لربط الأصناف", fontSize = 11.sp, color = Color(0xFF71817B))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = { IconButton(onClick = { refreshToken++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F7F6))
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF183B35)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("الوحدات المتاحة", color = Color(0xFFBFD9D0), fontSize = 12.sp)
                            Text(if (loading) "جارٍ التحميل…" else filtered.size.toString(), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            Text("من أصل ${units.size} وحدة نشطة", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                        }
                        Text("قراءة فقط", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("بحث بالرمز أو الاسم أو البُعد") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selectedDimension == "all", onClick = { selectedDimension = "all" }, label = { Text("الكل") }) }
                    items(dimensions.take(5)) { dimension ->
                        FilterChip(selected = selectedDimension == dimension, onClick = { selectedDimension = dimension }, label = { Text(dimension) })
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1EF)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("تعذر تحميل دليل الوحدات", color = Color(0xFF9C3028), fontWeight = FontWeight.Bold)
                        Text(error, color = Color(0xFF7B3933), fontSize = 12.sp)
                        OutlinedButton(onClick = { refreshToken++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && filtered.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE5EBE8)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (units.isEmpty()) "لا توجد وحدات نشطة متاحة للحساب." else "لا توجد نتائج مطابقة للبحث.", color = Color(0xFF183B35), fontWeight = FontWeight.SemiBold)
                        Text("لم يتم إنشاء وحدات تجريبية أو تعديل الدليل تلقائيًا.", color = Color(0xFF71817B), fontSize = 12.sp)
                    }
                }
            }
            items(filtered, key = { it.code }) { unit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE5EBE8)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(unit.label, color = Color(0xFF183B35), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("الرمز: ${unit.code}", color = Color(0xFF71817B), fontSize = 12.sp)
                            }
                            Surface(color = Color(0xFFE8F5EF), shape = RoundedCornerShape(9.dp)) {
                                Text("نشطة", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color(0xFF167D63), fontSize = 11.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = Color(0xFFF1F3F2), shape = RoundedCornerShape(8.dp)) {
                                Text("البُعد: ${unit.dimension.ifBlank { "غير محدد" }}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFF183B35), fontSize = 12.sp)
                            }
                            Surface(color = Color(0xFFF1F3F2), shape = RoundedCornerShape(8.dp)) {
                                Text("المنازل العشرية: ${unit.decimalPlaces}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFF183B35), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "هذا الدليل مشترك ويُعرض للقراءة فقط. اختيار الوحدة الأساسية لا يعرّف وحده مضاعفات العبوة؛ تحويل الكرتون أو الصندوق يحتاج معاملًا خاصًا بكل صنف.",
                    color = Color(0xFF71817B),
                    fontSize = 11.sp
                )
            }
        }
    }
}
