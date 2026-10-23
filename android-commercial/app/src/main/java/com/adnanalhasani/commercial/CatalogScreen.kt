package com.adnanalhasani.commercial

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal data class CatalogProduct(
    val id: String,
    val catalogCode: String,
    val name: String,
    val description: String,
    val categoryCode: String,
    val category: String,
    val brand: String,
    val manufacturer: String,
    val countryOfManufacture: String,
    val imageUrl: String,
    val prescriptionOnly: Boolean,
    val recommendationScore: Int,
    val barcode: String = "",
    val unitCode: String = "EA",
    val createdBy: String = ""
)

private val catalogIo = Executors.newSingleThreadExecutor()
private val catalogMain = Handler(Looper.getMainLooper())

private fun catalogHttp(path: String, payload: JSONObject, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) {
        "تحقق من إعداد SUPABASE_URL وSUPABASE_ANON_KEY."
    }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 25000
        doOutput = true
        setRequestProperty("apikey", anon)
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            val hint = runCatching { JSONObject(raw).optString("hint") }.getOrDefault("")
            throw IllegalStateException(listOf(message, hint).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "فشل الطلب إلى Supabase (HTTP $code)." })
        }
        raw
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun CatalogScreen(onBack: () -> Unit, onAddToInventory: (CatalogProduct) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var organizationId by remember { mutableStateOf("") }
    var sector by remember { mutableStateOf("retail") }
    var businessLevel by remember { mutableStateOf("shop") }
    var country by remember { mutableStateOf("YE") }
    var region by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var locale by remember { mutableStateOf("ar") }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    fun request(action: () -> String, onSuccess: (String) -> Unit) {
        if (busy) return
        busy = true
        feedback = ""
        catalogIo.execute {
            val result = runCatching(action)
            catalogMain.post {
                busy = false
                result.onSuccess(onSuccess).onFailure { feedback = it.message ?: "تعذر إكمال العملية." }
            }
        }
    }

    fun searchCatalog() {
        val activeToken = token ?: return
        if (organizationId.isBlank()) {
            feedback = "أدخل معرّف المؤسسة (UUID) التي تريد استعراض الدليل لها."
            return
        }
        request({
            val payload = JSONObject()
                .put("p_organization_id", organizationId.trim())
                .put("p_sector_code", sector.trim().ifBlank { JSONObject.NULL })
                .put("p_business_level_code", businessLevel.trim().ifBlank { JSONObject.NULL })
                .put("p_country_code", country.trim().ifBlank { JSONObject.NULL })
                .put("p_region_code", region.trim().ifBlank { JSONObject.NULL })
                .put("p_city", city.trim().ifBlank { JSONObject.NULL })
                .put("p_locale_code", locale.trim().ifBlank { "ar" })
                .put("p_query", query.trim().ifBlank { JSONObject.NULL })
                .put("p_limit", 100)
            catalogHttp("/rest/v1/rpc/commercial_catalog_discover_products", payload, activeToken)
        }, { raw ->
            val json = JSONArray(raw)
            items = List(json.length()) { i ->
                val row = json.getJSONObject(i)
                CatalogProduct(
                    id = row.optString("product_id"),
                    catalogCode = row.optString("catalog_code"),
                    name = row.optString("product_name"),
                    description = row.optString("short_description"),
                    categoryCode = row.optString("category_code"),
                    category = row.optString("category_name"),
                    brand = row.optString("brand"),
                    manufacturer = row.optString("manufacturer_name"),
                    countryOfManufacture = row.optString("country_of_manufacture"),
                    imageUrl = row.optString("image_url"),
                    prescriptionOnly = row.optBoolean("is_prescription_only", false),
                    recommendationScore = row.optInt("recommendation_score")
                )
            }
            feedback = "تم جلب ${items.size} صنفًا. القوالب العامة تحتاج إلى مراجعة بيانات المنتج قبل الاستخدام التجاري."
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
            Column(Modifier.weight(1f)) {
                Text("دليل الأصناف العالمي", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("بحث متعدد الأنشطة والوحدات والمناطق", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (token == null) {
            Text("تسجيل الدخول إلى حساب Supabase", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("البريد الإلكتروني") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("كلمة المرور") }, singleLine = true)
            Button(
                onClick = {
                    request({
                        val raw = catalogHttp("/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password))
                        val session = JSONObject(raw)
                        val access = session.optString("access_token")
                        if (access.isBlank()) throw IllegalStateException("لم تُرجع خدمة المصادقة جلسة صالحة.")
                        access
                    }, { access -> token = access; feedback = "تم تسجيل الدخول. أدخل معرّف المؤسسة للبحث." })
                },
                enabled = !busy && email.contains("@") && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "جارٍ تسجيل الدخول..." else "تسجيل الدخول") }
            Text("تُستخدم جلسة تسجيل الدخول في هذه الشاشة فقط، ولا تُحفظ كلمة المرور.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(organizationId, { organizationId = it.trim() }, Modifier.fillMaxWidth(), label = { Text("معرّف المؤسسة UUID") }, singleLine = true)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("بحث بالاسم أو الوصف أو الباركود أو الكود") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sector, { sector = it }, Modifier.weight(1f), label = { Text("النشاط / القطاع") }, singleLine = true)
                OutlinedTextField(businessLevel, { businessLevel = it }, Modifier.weight(1f), label = { Text("مستوى النشاط") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country, { country = it.uppercase() }, Modifier.weight(1f), label = { Text("الدولة") }, singleLine = true)
                OutlinedTextField(region, { region = it }, Modifier.weight(1f), label = { Text("المنطقة") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(city, { city = it }, Modifier.weight(1f), label = { Text("المدينة") }, singleLine = true)
                OutlinedTextField(locale, { locale = it }, Modifier.width(95.dp), label = { Text("اللغة") }, singleLine = true)
                Button(onClick = { searchCatalog() }, enabled = !busy) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(4.dp)); Text("بحث") }
            }
            Spacer(Modifier.height(8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (feedback.isNotBlank()) Text(feedback, color = if (feedback.startsWith("تم")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(items, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name.ifBlank { item.catalogCode }, fontWeight = FontWeight.SemiBold)
                                    Text("${item.category} • ${item.catalogCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                                if (item.recommendationScore > 0) AssistChip(onClick = {}, label = { Text("مقترح للنشاط +${item.recommendationScore}") })
                            }
                            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                            val facts = listOf(
                                item.brand.takeIf { it.isNotBlank() }?.let { "العلامة: $it" },
                                item.manufacturer.takeIf { it.isNotBlank() }?.let { "المصنّع: $it" },
                                item.countryOfManufacture.takeIf { it.isNotBlank() }?.let { "بلد الصناعة: $it" },
                                item.imageUrl.takeIf { it.isNotBlank() }?.let { "رابط الصورة: $it" },
                                if (item.prescriptionOnly) "صنف يتطلب وصفة طبية — تحقق من التصنيف المحلي" else null
                            ).filterNotNull()
                            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onAddToInventory(item) }) { Icon(Icons.Default.Add, null); Text("إضافة إلى المخزون المحلي") }
                            }
                        }
                    }
                }
            }
        }
        if (busy && token == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (feedback.isNotBlank() && token == null) Text(feedback, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
