package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AuditHistoryScreen(repository: HospitalRepository) {
    val entries by repository.recentAuditHistory().collectAsState(initial = emptyList())
    var localEntries by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var outboxEntries by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(repository) {
        localEntries = repository.localAuditRows()
        outboxEntries = repository.localFinanceOutboxRows()
    }
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("سجل تاريخ التعديلات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        Spacer(Modifier.height(6.dp))
        Text("يعرض آخر 500 عملية إضافة أو تعديل أو حذف مسجلة في قاعدة البيانات.", fontSize = 12.sp, color = Color(0xFF788895))
        Spacer(Modifier.height(12.dp))
        Text("سجل العمليات المحلية (SQLite)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        if (localEntries.isEmpty()) {
            Text("لا توجد عمليات محلية مسجلة.", fontSize = 12.sp, color = Color(0xFF788895))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(localEntries) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.first, fontWeight = FontWeight.SemiBold, color = Color(0xFF263B4D), fontSize = 12.sp)
                            Text(entry.second, color = Color(0xFF788895), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("طابور الأحداث المالية المحلية", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        Text("الأحداث تبقى محلياً بانتظار إضافة/تفعيل مزامنة آمنة مع الخادم.", fontSize = 11.sp, color = Color(0xFF788895))
        if (outboxEntries.isEmpty()) {
            Text("لا توجد أحداث مالية في الطابور.", fontSize = 12.sp, color = Color(0xFF788895))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(outboxEntries) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.first, fontWeight = FontWeight.SemiBold, color = Color(0xFF263B4D), fontSize = 12.sp)
                            Text(entry.second, color = Color(0xFF788895), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("سجل التعديلات العام", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
        if (entries.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF548F88), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("لا توجد عمليات مسجلة حتى الآن", color = Color(0xFF788895))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(when (entry.action) {
                                    "CREATE" -> "إضافة"
                                    "UPDATE" -> "تعديل"
                                    "DELETE" -> "حذف"
                                    "DEACTIVATE" -> "تعطيل عنصر مرجعي"
                                    else -> entry.action
                                }, color = when (entry.action) {
                                    "DELETE" -> Color(0xFFC97979)
                                    "DEACTIVATE" -> Color(0xFFD5AD6B)
                                    "UPDATE" -> Color(0xFFD5AD6B)
                                    else -> Color(0xFF548F88)
                                }, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("• ${entry.category} • #${entry.recordId}", color = Color(0xFF788895), fontSize = 11.sp)
                            }
                            Text(dateFormat.format(Date(entry.changedAt)), color = Color(0xFF788895), fontSize = 11.sp)
                            if (entry.oldTitle.isNotBlank() || entry.newTitle.isNotBlank()) {
                                Text("العنوان: ${entry.oldTitle.ifBlank { "—" }} ← ${entry.newTitle.ifBlank { "—" }}", color = Color(0xFF263B4D), fontSize = 13.sp)
                            }
                            if (entry.action == "UPDATE" && entry.oldDetails != entry.newDetails) {
                                HorizontalDivider(color = Color(0xFFE4EAEE))
                                Text("قبل التعديل", color = Color(0xFF788895), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(entry.oldDetails.ifBlank { "—" }, color = Color(0xFF263B4D), fontSize = 12.sp)
                                Text("بعد التعديل", color = Color(0xFF548F88), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(entry.newDetails.ifBlank { "—" }, color = Color(0xFF263B4D), fontSize = 12.sp)
                            }
                            if (entry.action == "DELETE" && entry.oldDetails.isNotBlank()) {
                                HorizontalDivider(color = Color(0xFFE4EAEE))
                                Text("التفاصيل المحفوظة قبل الحذف", color = Color(0xFF788895), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(entry.oldDetails, color = Color(0xFF263B4D), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
