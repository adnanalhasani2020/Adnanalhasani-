package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.Executors

private fun profileSessionPreferences(context: Context) = EncryptedSharedPreferences.create(
    context,
    "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private val profileIo = Executors.newSingleThreadExecutor()
private val profileMain = Handler(Looper.getMainLooper())

private data class BusinessActivityOption(
    val code: String, val nameAr: String, val nameEn: String, val pathAr: String, val defaultBusinessLevelCode: String
)

private fun supabaseRequest(path: String, payload: JSONObject, token: String? = null): JSONObject {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) {
        "أضف SUPABASE_URL وSUPABASE_ANON_KEY في إعدادات بناء Android أولًا."
    }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 20000
        doOutput = true
        setRequestProperty("apikey", anon)
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { "تعذر إكمال الطلب إلى Supabase (HTTP ${connection.responseCode})." })
        }
        if (raw.isBlank()) JSONObject() else if (raw.trimStart().startsWith("{")) JSONObject(raw) else JSONObject().put("result", raw)
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun ActivityProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var accountDisplayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf<String?>(null) }
    var isRegister by remember { mutableStateOf(false) }
    var organizationName by remember { mutableStateOf("") }
    var organizationSlug by remember { mutableStateOf("") }
    var activityName by remember { mutableStateOf("") }
    var activityCode by remember { mutableStateOf("") }
    val activityOptions = remember { mutableStateListOf<BusinessActivityOption>() }
    var activityDirectoryLoaded by remember { mutableStateOf(false) }
    var activityDirectoryError by remember { mutableStateOf("") }
    var activityType by remember { mutableStateOf("commercial") }
    var businessLevel by remember { mutableStateOf("shop") }
    var registrationNumber by remember { mutableStateOf("") }
    var openedOn by remember { mutableStateOf(LocalDate.now().toString()) }
    var ownerName by remember { mutableStateOf("") }
    var legalName by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("YE") }
    var timezone by remember { mutableStateOf("Asia/Aden") }
    var locale by remember { mutableStateOf("ar") }
    var currency by remember { mutableStateOf("YER") }
    var phone by remember { mutableStateOf("") }
    var publicEmail by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var logoUrl by remember { mutableStateOf("") }
    var coverImageUrl by remember { mutableStateOf("") }
    var identityFrontUrl by remember { mutableStateOf("") }
    var identityBackUrl by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    val branchNames = remember { mutableStateListOf<String>() }
    val branchTypes = remember { mutableStateListOf<String>() }
    val branchParents = remember { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var created by remember { mutableStateOf(false) }

    fun runRequest(action: () -> String, onSuccess: (String) -> Unit) {
        if (busy) return
        busy = true
        feedback = ""
        profileIo.execute {
            val result = runCatching(action)
            profileMain.post {
                busy = false
                result.onSuccess(onSuccess).onFailure { feedback = it.message ?: "تعذر إكمال العملية." }
            }
        }
    }

    LaunchedEffect(accessToken) {
        val token = accessToken ?: return@LaunchedEffect
        activityDirectoryLoaded = false
        profileIo.execute {
            val result = runCatching {
                supabaseRequest("/rest/v1/rpc/commercial_list_business_activity_nodes", JSONObject(), token)
            }
            profileMain.post {
                result.onSuccess { response ->
                    val nodes = response.optJSONArray("nodes") ?: JSONArray()
                    val loaded = buildList {
                        for (index in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(index) ?: continue
                            val code = node.optString("code")
                            if (code.isBlank()) continue
                            add(BusinessActivityOption(
                                code = code,
                                nameAr = node.optString("name_ar"),
                                nameEn = node.optString("name_en"),
                                pathAr = node.optString("path_ar", node.optString("name_ar")),
                                defaultBusinessLevelCode = node.optString("default_business_level_code")
                            ))
                        }
                    }
                    activityOptions.clear()
                    activityOptions.addAll(loaded)
                    activityDirectoryLoaded = true
                    activityDirectoryError = if (loaded.isEmpty()) "دليل الأنشطة فارغ؛ تحقق من ترحيل دليل الأنشطة." else ""
                }.onFailure {
                    activityDirectoryLoaded = true
                    activityDirectoryError = "تعذر تحميل دليل الأنشطة من الخادم. يمكنك إدخال الاسم يدويًا مؤقتًا."
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← رجوع إلى المتجر") }
        Text(if (created) "تم إنشاء ملف النشاط" else "إعداد النشاط التجاري", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("تطبيق Android أصلي • حفظ مباشر في قاعدة بيانات Supabase", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (accessToken == null) {
            Text("تسجيل الدخول إلى Supabase", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("البريد الإلكتروني") }, singleLine = true)
            if (isRegister) OutlinedTextField(accountDisplayName, { accountDisplayName = it }, Modifier.fillMaxWidth(), label = { Text("الاسم الذي تختاره في المنصة") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(if (isRegister) "كلمة المرور (12 حرفًا على الأقل)" else "كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(
                enabled = !busy && email.contains("@") && password.isNotBlank() && (!isRegister || (password.length >= 12 && accountDisplayName.trim().length >= 2)),
                onClick = {
                    runRequest({
                        val route = if (isRegister) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
                        val authPayload = JSONObject().put("email", email).put("password", password)
                        if (isRegister) authPayload.put("data", JSONObject().put("display_name", accountDisplayName.trim()))
                        val result = supabaseRequest(route, authPayload)
                        val session = result.optJSONObject("session") ?: result
                        val token = session.optString("access_token")
                        if (token.isBlank()) {
                            if (isRegister) throw IllegalStateException("تم إرسال طلب التسجيل. أكّد بريدك الإلكتروني ثم سجّل الدخول.")
                            throw IllegalStateException("لم تُرجع خدمة المصادقة جلسة صالحة.")
                        }
                        val refreshToken = session.optString("refresh_token")
                        if (refreshToken.isNotBlank()) {
                            profileSessionPreferences(context).edit().putString("refresh_token", refreshToken).apply()
                        }
                        accessToken = token
                        "تم تسجيل الدخول"
                    }, { feedback = it })
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "جارٍ الاتصال..." else if (isRegister) "إنشاء حساب" else "تسجيل الدخول") }
            TextButton(onClick = { isRegister = !isRegister; feedback = "" }) {
                Text(if (isRegister) "لدي حساب بالفعل — تسجيل الدخول" else "إنشاء حساب جديد")
            }
        } else if (!created) {
            Text("بيانات النشاط والمالك", style = MaterialTheme.typography.titleMedium)
            ProfileField("اسم النشاط *", organizationName) { organizationName = it; if (organizationSlug.isBlank()) organizationSlug = it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            ProfileField("الرابط المختصر بالإنجليزية *", organizationSlug) { organizationSlug = it.lowercase().trim() }
            if (activityOptions.isNotEmpty()) {
                ProfileChoice("النشاط التجاري من الدليل *", activityCode,
                    activityOptions.map { it.code to "${it.pathAr} • ${it.code}" }) { selectedCode ->
                    activityCode = selectedCode
                    activityOptions.firstOrNull { it.code == selectedCode }?.let { selected ->
                        activityName = selected.nameAr
                        if (selected.defaultBusinessLevelCode in setOf("company","institution","supermarket","grocery","stall","shop","office","factory","clinic","independent","other")) {
                            businessLevel = selected.defaultBusinessLevelCode
                        }
                    }
                }
                activityOptions.firstOrNull { it.code == activityCode }?.let { selected ->
                    Text("المسار: ${selected.pathAr}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else if (!activityDirectoryLoaded) {
                Text("جارٍ تحميل دليل الأنشطة من الخادم...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ProfileField("الاسم التفصيلي للنشاط *", activityName) { activityName = it }
                if (activityDirectoryError.isNotBlank()) Text(activityDirectoryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            ProfileChoice("نوع النشاط", activityType, listOf("commercial" to "تجاري", "service" to "خدمي", "production" to "إنتاجي")) { activityType = it }
            ProfileChoice("شكل النشاط", businessLevel, listOf("company" to "شركة", "institution" to "مؤسسة", "supermarket" to "سوبر ماركت", "grocery" to "بقالة", "stall" to "بسطة", "shop" to "محل تجاري", "office" to "مكتب", "factory" to "مصنع", "clinic" to "عيادة", "independent" to "نشاط فردي", "other" to "أخرى")) { businessLevel = it }
            ProfileField("رقم السجل التجاري *", registrationNumber) { registrationNumber = it }
            ProfileField("تاريخ الافتتاح YYYY-MM-DD *", openedOn) { openedOn = it }
            ProfileField("اسم مالك النشاط *", ownerName) { ownerName = it }
            ProfileField("الاسم القانوني (اختياري)", legalName) { legalName = it }
            ProfileField("الدولة (رمز ISO)", country) { country = it.uppercase() }
            ProfileChoice("المنطقة الزمنية", timezone, listOf("Asia/Aden" to "اليمن — عدن", "Asia/Riyadh" to "السعودية — الرياض", "Asia/Dubai" to "الإمارات — دبي", "UTC" to "UTC")) { timezone = it }
            ProfileChoice("اللغة", locale, listOf("ar" to "العربية", "en" to "English")) { locale = it }
            ProfileChoice("العملة", currency, listOf("YER" to "الريال اليمني", "SAR" to "الريال السعودي", "AED" to "الدرهم الإماراتي", "USD" to "الدولار الأمريكي")) { currency = it }
            Text("بيانات التواصل والعنوان", style = MaterialTheme.typography.titleMedium)
            ProfileField("هاتف النشاط", phone) { phone = it }
            ProfileField("البريد الإلكتروني للنشاط", publicEmail) { publicEmail = it }
            ProfileField("الموقع الإلكتروني https://...", website) { website = it }
            ProfileField("المدينة", city) { city = it }
            ProfileField("الحي", district) { district = it }
            ProfileField("الشارع والعنوان", street) { street = it }
            ProfileField("وصف النشاط", description) { description = it }
            Text("روابط الصور الخارجية", style = MaterialTheme.typography.titleMedium)
            Text("لا يتم رفع الصور أو الملفات إلى Supabase. ضع الصور في استضافتك الخارجية وأدخل روابط HTTPS فقط.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProfileField("رابط الشعار (HTTPS)", logoUrl) { logoUrl = it.trim() }
            ProfileField("رابط صورة الغلاف (HTTPS)", coverImageUrl) { coverImageUrl = it.trim() }
            ProfileField("رابط صورة الهوية الأمامية (HTTPS خاص)", identityFrontUrl) { identityFrontUrl = it.trim() }
            ProfileField("رابط صورة الهوية الخلفية (HTTPS خاص)", identityBackUrl) { identityBackUrl = it.trim() }
            Text("الفروع والمواقع التابعة", style = MaterialTheme.typography.titleMedium)
            Text("يُنشأ المقر الرئيسي تلقائيًا. يمكنك إضافة فروع أو مخازن أو أقسام وربط كل واحد بالمقر أو بفرع سابق.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            branchNames.indices.forEach { index ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("موقع تابع #${index + 1}", fontWeight = FontWeight.SemiBold)
                        ProfileField("اسم الفرع / القسم", branchNames[index]) { branchNames[index] = it }
                        ProfileChoice("نوع الموقع", branchTypes[index], listOf("branch" to "فرع", "store" to "متجر", "office" to "مكتب", "department" to "قسم", "warehouse" to "مخزن", "factory" to "مصنع", "other" to "أخرى")) { branchTypes[index] = it }
                        ProfileChoice("يتبع", branchParents[index].toString(), listOf(-1 to "المقر الرئيسي").map { it.first.toString() to it.second } + (0 until index).map { it.toString() to "موقع #${it + 1}" }) { branchParents[index] = it.toIntOrNull() ?: -1 }
                        TextButton(onClick = { branchNames.removeAt(index); branchTypes.removeAt(index); branchParents.removeAt(index); for (i in branchParents.indices) if (branchParents[i] >= index) branchParents[i] = if (branchParents[i] == index) -1 else branchParents[i] - 1 }) { Text("حذف الموقع") }
                    }
                }
            }
            OutlinedButton(onClick = { branchNames.add(""); branchTypes.add("branch"); branchParents.add(-1) }, modifier = Modifier.fillMaxWidth()) { Text("+ إضافة فرع / موقع") }
            Text("لن يتم إنشاء منتجات أو مخزون في هذه الخطوة. تُحفظ بيانات الملف في Supabase عبر دالة RPC محمية بجلسة المستخدم.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                enabled = !busy && organizationName.length >= 2 && organizationSlug.matches(Regex("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) && activityName.isNotBlank() && (activityOptions.isEmpty() || activityCode.isNotBlank()) && registrationNumber.isNotBlank() && ownerName.length >= 2 && branchNames.all { it.isNotBlank() } && listOf(logoUrl, coverImageUrl, identityFrontUrl, identityBackUrl).all(::validOptionalHttpsUrl) && runCatching { LocalDate.parse(openedOn); true }.getOrDefault(false),
                onClick = {
                    val token = accessToken ?: return@Button
                    runRequest({
                        val payload = JSONObject()
                            .put("p_organization_name", organizationName.trim())
                            .put("p_organization_slug", organizationSlug)
                            .put("p_activity_name", activityName.trim())
                            .put("p_activity_type", activityType)
                            .put("p_business_level", businessLevel)
                            .put("p_commercial_registration_number", registrationNumber.trim())
                            .put("p_opened_on", openedOn)
                            .put("p_owner_full_name", ownerName.trim())
                            .put("p_legal_name", legalName.ifBlank { JSONObject.NULL })
                            .put("p_country_code", country)
                            .put("p_timezone", timezone)
                            .put("p_locale", locale)
                            .put("p_currency_code", currency)
                            .put("p_public_phone", phone.ifBlank { JSONObject.NULL })
                            .put("p_public_email", publicEmail.ifBlank { JSONObject.NULL })
                            .put("p_website_url", website.ifBlank { JSONObject.NULL })
                            .put("p_public_description", description.ifBlank { JSONObject.NULL })
                            .put("p_city", city.ifBlank { JSONObject.NULL })
                            .put("p_district", district.ifBlank { JSONObject.NULL })
                            .put("p_street", street.ifBlank { JSONObject.NULL })
                            .put("p_landmark", JSONObject.NULL)
                            .put("p_logo_url", logoUrl.trim().ifBlank { JSONObject.NULL })
                            .put("p_cover_image_url", coverImageUrl.trim().ifBlank { JSONObject.NULL })
                            .put("p_owner_identity_front_url", identityFrontUrl.trim().ifBlank { JSONObject.NULL })
                            .put("p_owner_identity_back_url", identityBackUrl.trim().ifBlank { JSONObject.NULL })
                        val locations = JSONArray()
                        branchNames.forEachIndexed { index, name ->
                            if (name.isNotBlank()) locations.put(JSONObject().put("name", name.trim()).put("location_type", branchTypes[index]).put("parent_index", branchParents[index]))
                        }
                        payload.put("p_locations", locations)
                        val result = supabaseRequest("/rest/v1/rpc/commercial_create_activity_profile", payload, token)
                        val organizationId = result.optString("organization_id")
                        val activityId = result.optString("activity_id")
                        val locationId = result.optString("location_id")
                        if (organizationId.isBlank() || activityId.isBlank() || locationId.isBlank()) {
                            throw IllegalStateException("استجابة قاعدة البيانات لا تحتوي معرّفات المؤسسة والنشاط والموقع.")
                        }
                        context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE).edit()
                            .putString("organization_id", organizationId)
                            .putString("activity_id", activityId)
                            .putString("location_id", locationId)
                            .putString("currency_code", currency)
                            .putString("country_code", country)
                            .apply()
                        "تم حفظ ملف النشاط ومعرّفاته في قاعدة البيانات بنجاح."
                    }, { feedback = it; created = it.startsWith("تم حفظ") })
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "جارٍ الحفظ في قاعدة البيانات..." else "حفظ ملف النشاط") }
        } else {
            Text("تم إنشاء ملف النشاط وحفظه في Supabase. يمكنك العودة إلى واجهة المتجر.", color = MaterialTheme.colorScheme.primary)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("العودة إلى المتجر") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (feedback.isNotBlank()) Text(feedback, color = if (created) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProfileField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileChoice(label: String, selected: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.second) }, onClick = { onSelected(option.first); expanded = false })
            }
        }
    }
}

private fun validOptionalHttpsUrl(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isEmpty() || (trimmed.startsWith("https://", ignoreCase = true) && Patterns.WEB_URL.matcher(trimmed).matches() && trimmed.length <= 500)
}
