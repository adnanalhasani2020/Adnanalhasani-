package com.adnanalhasani.commercial

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

private val adsIo = Executors.newSingleThreadExecutor()
private val adsMain = Handler(Looper.getMainLooper())

private fun adsSessionPrefs(context: Context) = EncryptedSharedPreferences.create(
    context, "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private fun adsHttp(context: Context, path: String, payload: JSONObject, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات Supabase غير مكتملة في هذا البناء." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", anon); setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { "تعذر تنفيذ العملية (HTTP $status)." })
        }
        raw
    } finally { connection.disconnect() }
}

private fun adsAccessToken(context: Context): String {
    val prefs = adsSessionPrefs(context)
    val saved = prefs.getString("refresh_token", null).orEmpty()
    require(saved.isNotBlank()) { "سجّل الدخول إلى حساب المنصة أولًا." }
    val session = runCatching {
        JSONObject(adsHttp(context, "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", saved)))
    }.getOrElse { throw IllegalStateException("انتهت جلسة الدخول أو تعذر تجديدها؛ سجّل الدخول مجددًا.") }
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الدخول؛ سجّل الدخول مجددًا." }
    session.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
        prefs.edit().putString("refresh_token", it).apply()
    }
    return access
}

private data class CommercialAdRow(
    val id: String, val merchantName: String, val title: String, val product: String,
    val description: String, val discount: String, val reason: String, val endsAt: String,
    val status: String, val audience: String
)

private fun adRow(row: JSONObject) = CommercialAdRow(
    row.optString("id"), row.optString("merchant_name"), row.optString("title"),
    row.optString("product_or_offer"), row.optString("description"),
    row.optString("discount_percent"), row.optString("discount_reason"),
    row.optString("ends_at"), row.optString("status"), row.optString("target_audience")
)

private fun loadAdFeed(context: Context, token: String, lat: Double?, lon: Double?, gender: String, age: Int?): List<CommercialAdRow> {
    val payload = JSONObject().put("p_viewer_latitude", lat ?: JSONObject.NULL)
        .put("p_viewer_longitude", lon ?: JSONObject.NULL).put("p_viewer_gender", gender)
        .put("p_viewer_age", age ?: JSONObject.NULL).put("p_limit", 30)
    val array = JSONArray(adsHttp(context, "/rest/v1/rpc/commercial_ad_campaigns_feed_my", payload, token))
    return buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(adRow(it)) } }
}

@Composable
private fun AdOfferCard(row: CommercialAdRow, compact: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.merchantName.isNotBlank()) Text(row.merchantName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(row.title, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.product, modifier = Modifier.weight(1f))
                if (row.discount.toDoubleOrNull()?.let { it > 0 } == true) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("خصم ${row.discount}%", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (row.description.isNotBlank()) Text(row.description, style = MaterialTheme.typography.bodySmall)
            if (row.reason.isNotBlank()) Text("سبب العرض: ${row.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact && row.endsAt.isNotBlank()) Text("ينتهي: ${row.endsAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CommercialAdsHomePreview(context: Context, onOpen: () -> Unit) {
    var rows by remember { mutableStateOf<List<CommercialAdRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        adsIo.execute {
            val result = runCatching { loadAdFeed(context, adsAccessToken(context), null, null, "all", null) }
            adsMain.post { result.onSuccess { rows = it.take(2) } }
        }
    }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("الإعلانات والعروض", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text("عرض الكل") }
            }
            if (rows.isEmpty()) {
                Text("اكتشف عروض المتاجر القريبة والعروض المتاحة لك.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(row.product, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (row.discount.toDoubleOrNull()?.let { it > 0 } == true) Text("${row.discount}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdsChoiceField(label: String, selected: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = display, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelected(value); expanded = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialAdsScreen(context: Context, onBack: () -> Unit) {
    val cloudPrefs = remember { context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE) }
    val organizationId = remember { cloudPrefs.getString("organization_id", null).orEmpty() }
    val locationId = remember { cloudPrefs.getString("location_id", null).orEmpty() }
    val preferences = remember { context.getSharedPreferences("commercial_ads_preferences_v1", Context.MODE_PRIVATE) }
    var viewerGender by remember { mutableStateOf(preferences.getString("gender", "all").orEmpty()) }
    var viewerAgeText by remember { mutableStateOf(preferences.getInt("age", -1).takeIf { it >= 0 }?.toString().orEmpty()) }
    var viewerLatitude by remember { mutableStateOf<Double?>(null) }
    var viewerLongitude by remember { mutableStateOf<Double?>(null) }
    var feed by remember { mutableStateOf<List<CommercialAdRow>>(emptyList()) }
    var mine by remember { mutableStateOf<List<CommercialAdRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(action: (String) -> String, success: (String) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; notice = ""
        adsIo.execute {
            val result = runCatching { action(adsAccessToken(context)) }
            adsMain.post {
                busy = false
                result.onSuccess(success).onFailure { error = it.message ?: "تعذر إكمال العملية." }
            }
        }
    }
    fun refresh() {
        run({ token ->
            val currentFeed = loadAdFeed(context, token, viewerLatitude, viewerLongitude, viewerGender, viewerAgeText.toIntOrNull())
            val mineRaw = if (organizationId.isNotBlank()) adsHttp(context, "/rest/v1/rpc/commercial_ad_campaigns_list_my",
                JSONObject().put("p_organization_id", organizationId).put("p_limit", 50), token) else "[]"
            JSONObject().put("feed", JSONArray().also { a -> currentFeed.forEach { row ->
                a.put(JSONObject().put("id",row.id).put("merchant_name",row.merchantName).put("title",row.title)
                    .put("product_or_offer",row.product).put("description",row.description).put("discount_percent",row.discount)
                    .put("discount_reason",row.reason).put("ends_at",row.endsAt).put("status",row.status).put("target_audience",row.audience))
            } }).put("mine", JSONArray(mineRaw)).toString()
        }) { raw ->
            val result = JSONObject(raw)
            feed = buildList { val a=result.optJSONArray("feed") ?: JSONArray(); for(i in 0 until a.length()) a.optJSONObject(i)?.let { add(adRow(it)) } }
            mine = buildList { val a=result.optJSONArray("mine") ?: JSONArray(); for(i in 0 until a.length()) a.optJSONObject(i)?.let { add(adRow(it)) } }
            notice = if (feed.isEmpty()) "لا توجد عروض تنطبق على خياراتك وموقعك الحالي." else ""
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch {
                runCatching { getFreshCatalogDeviceLocation(context) }
                    .onSuccess { viewerLatitude = it.latitude; viewerLongitude = it.longitude; refresh() }
                    .onFailure { error = it.message ?: "تعذر تحديد موقعك." }
            }
        } else {
            viewerLatitude = null; viewerLongitude = null
            error = "لم تمنح إذن الموقع؛ ستظهر العروض العامة فقط."
            refresh()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("الإعلانات والعروض", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } })
    }, floatingActionButton = {
        if (organizationId.isNotBlank() && locationId.isNotBlank()) {
            ExtendedFloatingActionButton(onClick = { createOpen = true }, icon = { Icon(Icons.Default.Campaign, null) }, text = { Text("إنشاء إعلان") })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) { Text(error); TextButton(onClick = { refresh() }) { Text("إعادة المحاولة") } }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("تخصيص العروض", fontWeight = FontWeight.Bold)
                        Text("النوع والعمر اختياريان. تحفظ اختياراتك على هذا الجهاز فقط، وتُرسل مع طلب جلب العروض لتطبيق الاستهداف.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AdsChoiceField("الجنس", viewerGender, listOf("all" to "الكل / لا أحدد", "male" to "ذكر", "female" to "أنثى")) {
                            viewerGender = it; preferences.edit().putString("gender", it).apply()
                        }
                        OutlinedTextField(value = viewerAgeText, onValueChange = {
                            viewerAgeText = it.filter(Char::isDigit).take(3)
                            preferences.edit().putInt("age", viewerAgeText.toIntOrNull() ?: -1).apply()
                        }, label = { Text("العمر (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                                Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(5.dp)); Text(if (viewerLatitude == null) "العروض القريبة" else "تحديث موقعي")
                            }
                            Text(if (viewerLatitude == null) "بدون موقع: العروض العامة فقط" else "موقع تقريبي لهذه الجلسة",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(enabled = !busy, onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("تطبيق الفلاتر") }
                    }
                }
            }
            if (notice.isNotBlank()) item { Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("العروض المتاحة", fontWeight = FontWeight.Bold) }
            items(feed, key = { it.id }) { AdOfferCard(it) }
            if (organizationId.isNotBlank()) {
                item { HorizontalDivider(); Text("إعلانات متجرك", fontWeight = FontWeight.Bold) }
                if (mine.isEmpty()) item { Text("لم تنشئ إعلانات لهذا الفرع بعد.", style = MaterialTheme.typography.bodySmall) }
                items(mine, key = { "mine-" + it.id }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title, fontWeight = FontWeight.Bold)
                            Text("${row.product} • ${row.discount}%", style = MaterialTheme.typography.bodySmall)
                            Text("الحالة: " + when(row.status) { "active" -> "نشط"; "paused" -> "متوقف"; else -> "منتهي" }, style = MaterialTheme.typography.labelSmall)
                            if (row.status != "ended") {
                                TextButton(enabled = !busy, onClick = {
                                    val next = if (row.status == "active") "paused" else "active"
                                    run({ token -> adsHttp(context, "/rest/v1/rpc/commercial_ad_campaigns_set_status_my",
                                        JSONObject().put("p_organization_id", organizationId).put("p_campaign_id", row.id).put("p_status", next), token) }) {
                                        notice = if (next == "paused") "تم إيقاف الإعلان." else "تم تفعيل الإعلان."
                                        refresh()
                                    }
                                }) {
                                    Icon(if (row.status == "active") Icons.Default.PauseCircle else Icons.Default.PlayCircle, null)
                                    Spacer(Modifier.width(4.dp)); Text(if (row.status == "active") "إيقاف مؤقت" else "إعادة التفعيل")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        var title by remember { mutableStateOf("") }
        var product by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var discount by remember { mutableStateOf("0") }
        var reason by remember { mutableStateOf("") }
        var audience by remember { mutableStateOf("all") }
        var activity by remember { mutableStateOf("") }
        var gender by remember { mutableStateOf("all") }
        var minAge by remember { mutableStateOf("") }
        var maxAge by remember { mutableStateOf("") }
        var radius by remember { mutableStateOf("25") }
        var startsAt by remember { mutableStateOf(Instant.now().plusSeconds(60).toString()) }
        var endsAt by remember { mutableStateOf(Instant.now().plusSeconds(2 * 86400L).toString()) }
        var validation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createOpen = false }, title = { Text("إنشاء إعلان أو عرض") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title=it.take(160); validation="" }, label={Text("عنوان الإعلان")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(product, { product=it.take(180); validation="" }, label={Text("المنتج أو العرض")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description=it.take(4000) }, label={Text("وصف العرض")}, minLines=2, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(discount, { discount=it.filter { ch -> ch.isDigit() || ch=='.' }.take(6) }, label={Text("نسبة التخفيض % (0 إلى 100)")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(reason, { reason=it.take(500) }, label={Text("سبب التخفيض")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                    AdsChoiceField("الجمهور", audience, listOf("all" to "الكل", "traders" to "التجار", "consumers" to "المستهلكون", "customers" to "عملاء المتجر")) { audience=it }
                    AdsChoiceField("النشاط", activity, listOf("" to "كل الأنشطة", "commercial" to "تجاري", "service" to "خدمات", "production" to "إنتاج")) { activity=it }
                    AdsChoiceField("الجنس", gender, listOf("all" to "الكل", "male" to "ذكر", "female" to "أنثى")) { gender=it }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(minAge,{minAge=it.filter(Char::isDigit).take(3)},label={Text("من عمر")},modifier=Modifier.weight(1f),singleLine=true)
                        OutlinedTextField(maxAge,{maxAge=it.filter(Char::isDigit).take(3)},label={Text("إلى عمر")},modifier=Modifier.weight(1f),singleLine=true)
                    }
                    OutlinedTextField(radius,{radius=it.filter { ch -> ch.isDigit() || ch=='.' }.take(6)},label={Text("نطاق الاستهداف بالكيلومتر (فارغ = عام)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(startsAt,{startsAt=it.take(40);validation=""},label={Text("بداية الإعلان ISO-8601")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(endsAt,{endsAt=it.take(40);validation=""},label={Text("نهاية الإعلان ISO-8601")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("يستخدم الاستهداف الجغرافي إحداثيات الفرع الحالي. إذا لم يكن للفرع موقع محفوظ، اترك النطاق فارغًا أو حدّث إحداثيات الفرع أولًا.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if (validation.isNotBlank()) Text(validation,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(enabled=!busy,onClick={
                    val percent=discount.toDoubleOrNull()
                    val min=minAge.toIntOrNull(); val max=maxAge.toIntOrNull(); val radiusValue=radius.toDoubleOrNull()
                    val start=runCatching{Instant.parse(startsAt.trim())}.getOrNull()
                    val end=runCatching{Instant.parse(endsAt.trim())}.getOrNull()
                    when {
                        organizationId.isBlank() || locationId.isBlank() -> validation="حدد المؤسسة والفرع الحاليين أولًا."
                        title.trim().length<2 || product.trim().length<2 -> validation="أدخل عنوان الإعلان واسم المنتج أو العرض."
                        percent==null || percent !in 0.0..100.0 -> validation="نسبة التخفيض يجب أن تكون بين 0 و100."
                        (min!=null && min !in 0..120)||(max!=null && max !in 0..120)||(min!=null && max!=null && min>max) -> validation="راجع نطاق الأعمار."
                        (radius.isNotBlank() && (radiusValue==null || radiusValue !in 1.0..500.0)) -> validation="نطاق الاستهداف يجب أن يكون بين 1 و500 كم، أو اتركه فارغًا."
                        start==null || end==null || end<=start || end<=Instant.now() -> validation="تحقق من وقت بداية الإعلان ونهايته."
                        else -> {
                            val payload=JSONObject().put("p_organization_id",organizationId).put("p_location_id",locationId)
                                .put("p_title",title.trim()).put("p_product_or_offer",product.trim()).put("p_description",description.trim())
                                .put("p_discount_percent",percent).put("p_discount_reason",reason.trim()).put("p_target_audience",audience)
                                .put("p_target_activity_type",if(activity.isBlank()) JSONObject.NULL else activity).put("p_target_gender",gender)
                                .put("p_min_age",min?:JSONObject.NULL).put("p_max_age",max?:JSONObject.NULL)
                                .put("p_radius_km",radiusValue?:JSONObject.NULL).put("p_starts_at",start.toString()).put("p_ends_at",end.toString())
                            run({token->adsHttp(context,"/rest/v1/rpc/commercial_ad_campaigns_create",payload,token)}) {
                                createOpen=false;notice="تم إنشاء الإعلان.";refresh()
                            }
                        }
                    }
                }) { Text("نشر الإعلان") }
            },
            dismissButton = { TextButton(onClick={createOpen=false}) { Text("إلغاء") } }
        )
    }
}
