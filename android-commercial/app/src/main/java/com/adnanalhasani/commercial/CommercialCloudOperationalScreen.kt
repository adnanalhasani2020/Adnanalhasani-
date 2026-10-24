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
import java.util.Locale
import java.util.concurrent.Executors

private val CloudScreenIo = Executors.newSingleThreadExecutor()
private val CloudScreenMain = Handler(Looper.getMainLooper())

private data class CloudScreenSpec(val title: String, val subtitle: String, val key: String, val empty: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudOperationalScreen(
    context: Context,
    screenKey: String,
    onBack: () -> Unit
) {
    val spec = when (screenKey) {
        "customers" -> CloudScreenSpec("العملاء السحابيون", "بيانات العملاء من المؤسسة الحالية", screenKey, "لا يوجد عملاء مسجلون في السحابة.")
        "suppliers" -> CloudScreenSpec("دليل الموردين", "بيانات الموردين وشروط السداد المحفوظة", screenKey, "لا يوجد موردون في السحابة.")
        "purchases" -> CloudScreenSpec("أوامر الشراء", "متابعة حالات أوامر الشراء من الخادم", screenKey, "لا توجد أوامر شراء مسجلة.")
        "payments" -> CloudScreenSpec("السندات والمدفوعات", "حركات الدفع المحفوظة على الخادم", screenKey, "لا توجد مدفوعات مسجلة.")
        "returns" -> CloudScreenSpec("مرتجعات المبيعات", "متابعة حالة الطلب والاسترداد وإعادة المخزون", screenKey, "لا توجد طلبات مرتجعات.")
        "journals" -> CloudScreenSpec("القيود المحاسبية", "عرض القيود المسجلة وحالتها دون تعديل التاريخ المالي", screenKey, "لا توجد قيود ظاهرة لهذا الحساب.")
        else -> CloudScreenSpec("دليل الحسابات", "الحسابات المحفوظة في المؤسسة الحالية", "accounts", "لا توجد حسابات ظاهرة.")
    }
    var rows by remember(screenKey) { mutableStateOf<List<CommercialCloudRecord>>(emptyList()) }
    var loading by remember(screenKey) { mutableStateOf(true) }
    var error by remember(screenKey) { mutableStateOf("") }
    var query by remember(screenKey) { mutableStateOf("") }
    var refreshToken by remember(screenKey) { mutableIntStateOf(0) }

    LaunchedEffect(screenKey, refreshToken) {
        loading = true
        error = ""
        CloudScreenIo.execute {
            val result = runCatching { loadCommercialCloudRecords(context, spec.key) }
            CloudScreenMain.post {
                result.onSuccess { rows = it }
                    .onFailure { error = it.message ?: "تعذر تحميل البيانات من الخادم." }
                loading = false
            }
        }
    }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows else rows.filter {
            listOf(it.title, it.subtitle, it.status, it.id).any { value -> value.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F7F6),
        topBar = {
            TopAppBar(
                title = { Column { Text(spec.title, fontWeight = FontWeight.Bold); Text(spec.subtitle, fontSize = 11.sp, color = Color(0xFF71817B)) } },
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
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("بحث في السجلات") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(14.dp)
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF183B35)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("سجلات الخادم", color = Color(0xFFBFD9D0), fontSize = 12.sp)
                            Text(if (loading) "جارٍ التحميل…" else filtered.size.toString(), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("قراءة فقط", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                    }
                }
            }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1EF)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("تعذر تحميل بيانات السحابة", color = Color(0xFF9C3028), fontWeight = FontWeight.Bold)
                        Text(error, color = Color(0xFF7B3933), fontSize = 12.sp)
                        OutlinedButton(onClick = { refreshToken++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && filtered.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE5EBE8)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(spec.empty, color = Color(0xFF183B35), fontWeight = FontWeight.SemiBold)
                        Text("لم يتم إنشاء بيانات تجريبية تلقائيًا، ولم يتم استبدال بيانات الخادم ببيانات محلية.", color = Color(0xFF71817B), fontSize = 12.sp)
                    }
                }
            }
            items(filtered, key = { it.id }) { row ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE5EBE8)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, color = Color(0xFF183B35), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (row.subtitle.isNotBlank()) Text(row.subtitle, color = Color(0xFF71817B), fontSize = 12.sp)
                            }
                            Surface(color = if (row.status.lowercase(Locale.ROOT) in setOf("active", "posted", "paid", "approved", "completed", "نشط")) Color(0xFFE8F5EF) else Color(0xFFF1F3F2), shape = RoundedCornerShape(9.dp)) {
                                Text(row.status, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color(0xFF183B35), fontSize = 11.sp)
                            }
                        }
                        if (row.amountMinor > 0L) Text("المبلغ: " + String.format(Locale.US, "%,d", row.amountMinor) + " وحدة نقدية صغرى" , color = Color(0xFF167D63), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (row.createdAt.isNotBlank()) Text(row.createdAt.replace('T', ' ').take(19), color = Color(0xFF71817B), fontSize = 10.sp)
                        Text("مرجع: " + row.id.take(8), color = Color(0xFF71817B), fontSize = 10.sp)
                    }
                }
            }
            item { Text("تُعرض البيانات وفق صلاحيات جلسة Supabase وسياسات قاعدة البيانات. عمليات الإنشاء والترحيل والتسوية لا تُفعّل حتى اعتماد عقد الكتابة الخاص بها.", color = Color(0xFF71817B), fontSize = 11.sp) }
        }
    }
}
