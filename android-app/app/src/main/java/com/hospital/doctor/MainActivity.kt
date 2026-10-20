package com.hospital.doctor

import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private data class Patient(val id: Int, val fileNo: String, val name: String, val birthDate: String, val sex: String, val phone: String, val allergies: String)
    private data class QueueItem(val patient: Patient, val time: String, val status: String, val kind: String, val recordId: Int, val reason: String, val doctor: String)
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("hospital_doctor_api", MODE_PRIVATE) }
    private var baseUrl = ""
    private var bearer = ""
    private var activeTab = "today"
    private var selectedDate = Calendar.getInstance()
    private var patients = emptyList<Patient>()
    private var queueItems = emptyList<QueueItem>()
    private lateinit var patientList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var listTitle: TextView
    private lateinit var dateButton: Button
    private lateinit var countLabel: TextView
    private lateinit var connectionLabel: TextView
    private val navy = Color.rgb(15, 63, 116)
    private val blue = Color.rgb(21, 101, 192)
    private val ink = Color.rgb(27, 45, 64)
    private val muted = Color.rgb(103, 121, 140)
    private val border = Color.rgb(225, 233, 241)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        patientList = findViewById(R.id.patientList)
        searchInput = findViewById(R.id.searchInput)
        listTitle = findViewById(R.id.listTitle)
        dateButton = findViewById(R.id.dateButton)
        countLabel = TextView(this).apply { textSize = 12f; setTextColor(muted); gravity = Gravity.END; setPadding(4, 0, 4, 8) }
        val parent = listTitle.parent as ViewGroup
        parent.addView(countLabel, parent.indexOfChild(listTitle) + 1)
        connectionLabel = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(dp(14), dp(6), dp(14), dp(6)); text = "لم يتم الاتصال بالخادم" }
        parent.addView(connectionLabel, parent.indexOfChild(countLabel) + 1)
        findViewById<Button>(R.id.tabToday).setOnClickListener { selectTab("today") }
        findViewById<Button>(R.id.tabUpcoming).setOnClickListener { selectTab("upcoming") }
        findViewById<Button>(R.id.tabCompleted).setOnClickListener { selectTab("completed") }
        dateButton.setOnClickListener { showDatePicker() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderPatients()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<TextView>(R.id.configureConnection).setOnClickListener { showConnectionDialog() }
        baseUrl = prefs.getString("base_url", "") ?: ""
        bearer = prefs.getString("bearer", "") ?: ""
        updateDateLabel()
        styleTabButtons()
        renderPatients()
        if (baseUrl.isBlank() || bearer.isBlank()) showConnectionDialog() else loadQueue()
    }

    private fun selectTab(tab: String) {
        activeTab = tab
        listTitle.text = when (tab) { "upcoming" -> "المواعيد القادمة"; "completed" -> "الزيارات المنتهية"; else -> "المرضى المنتظرون" }
        styleTabButtons()
        renderPatients()
    }

    private fun styleTabButtons() {
        listOf(R.id.tabToday to "today", R.id.tabUpcoming to "upcoming", R.id.tabCompleted to "completed").forEach { pair ->
            val button = findViewById<Button>(pair.first)
            val active = activeTab == pair.second
            button.setTextColor(if (active) blue else muted)
            button.textSize = 12f
            button.isAllCaps = false
            button.background = rounded(if (active) Color.WHITE else Color.rgb(235, 241, 248), 12, if (active) blue else Color.TRANSPARENT)
            button.elevation = if (active) dp(2).toFloat() else 0f
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(this, { _, year, month, day ->
            selectedDate.set(year, month, day)
            updateDateLabel()
            renderPatients()
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateDateLabel() {
        dateButton.text = "▦   " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.time) + "     اختيار التاريخ"
        dateButton.isAllCaps = false
        dateButton.textSize = 14f
        dateButton.setTextColor(navy)
        dateButton.background = rounded(Color.WHITE, 12, border)
    }

    private fun showConnectionDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val urlInput = EditText(this).apply {
            hint = "عنوان API، مثال: https://hospital.example"
            setSingleLine(true); setText(baseUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val tokenInput = EditText(this).apply {
            hint = "رمز Bearer أو رمز API"; setSingleLine(true); setText(bearer)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(urlInput); box.addView(tokenInput)
        box.addView(TextView(this).apply {
            text = "أدخل عنوان الخادم ورمز المصادقة. لا تستخدم بيانات مرضى حقيقية عبر HTTP غير مشفّر. للمحاكي المحلي استخدم http://10.0.2.2:8080."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this).setTitle("الاتصال بقاعدة بيانات المستشفى").setView(box)
            .setPositiveButton("حفظ والاتصال") { _, _ ->
                val u = urlInput.text.toString().trim().trimEnd('/')
                val t = tokenInput.text.toString().trim()
                if (u.isBlank() || t.isBlank() || !(u.startsWith("https://") || u.startsWith("http://"))) {
                    toast("أدخل عنوان API صحيحًا ورمز المصادقة.")
                } else {
                    baseUrl = u; bearer = t
                    prefs.edit().putString("base_url", baseUrl).putString("bearer", bearer).apply()
                    loadQueue()
                }
            }.setNeutralButton("إلغاء", null).show()
    }

    private fun loadQueue() {
        if (baseUrl.isBlank() || bearer.isBlank()) { showConnectionDialog(); return }
        connectionLabel.text = "جارٍ الاتصال بالخادم..."
        io.execute {
            try {
                val pj = apiGet("/api/patients?limit=100&offset=0").optJSONArray("data") ?: JSONArray()
                val ej = apiGet("/api/encounters?limit=100&offset=0").optJSONArray("data") ?: JSONArray()
                val aj = apiGet("/api/appointments?limit=100&offset=0").optJSONArray("data") ?: JSONArray()
                val pmap = mutableMapOf<Int, Patient>()
                for (i in 0 until pj.length()) {
                    val j = pj.getJSONObject(i)
                    val p = Patient(j.optInt("id"), j.optString("patient_no"), j.optString("full_name"), j.optString("date_of_birth"), j.optString("sex"), j.optString("phone"), j.optString("allergies"))
                    pmap[p.id] = p
                }
                val rows = mutableListOf<QueueItem>()
                for (i in 0 until ej.length()) {
                    val j = ej.getJSONObject(i); val p = pmap[j.optInt("patient_id")] ?: continue
                    rows.add(QueueItem(p, j.optString("opened_at"), j.optString("status"), "encounter", j.optInt("id"), j.optString("chief_complaint"), j.optString("doctor_name")))
                }
                for (i in 0 until aj.length()) {
                    val j = aj.getJSONObject(i); val p = pmap[j.optInt("patient_id")] ?: continue
                    rows.add(QueueItem(p, j.optString("starts_at"), j.optString("status"), "appointment", j.optInt("id"), j.optString("reason"), j.optString("doctor_name")))
                }
                patients = pmap.values.toList()
                queueItems = rows.sortedBy { it.time }
                runOnUiThread {
                    connectionLabel.text = "تم الاتصال بالخادم • " + patients.size + " مريض"
                    renderPatients()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectionLabel.text = "تعذر الاتصال: " + (e.message ?: "تحقق من الشبكة")
                    toast("فشل تحميل البيانات. تحقق من عنوان الخادم والرمز والصلاحيات.")
                    renderPatients()
                }
            }
        }
    }

    private fun apiGet(path: String): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer " + bearer)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val error = try { JSONObject(body).optString("error", "HTTP " + code) } catch (_: Exception) { "HTTP " + code }
                throw IllegalStateException(if (code == 401) "المصادقة مرفوضة (401)" else if (code == 403) "الحساب لا يملك الصلاحية (403)" else error)
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun renderPatients() {
        patientList.removeAllViews()
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.time)
        val filtered = queueItems.filter { item ->
            val p = item.patient
            val matchesText = (p.name + " " + p.fileNo + " " + p.phone).lowercase(Locale.getDefault()).contains(query)
            val status = item.status.lowercase(Locale.US)
            val date = item.time.take(10)
            val matchesTab = when (activeTab) {
                "completed" -> status == "completed" || (item.kind == "encounter" && status == "closed")
                "upcoming" -> item.kind == "appointment" && (status == "scheduled" || status == "checked_in") && date >= day
                else -> status != "completed" && status != "closed" && status != "cancelled" && status != "no_show" && date.startsWith(day)
            }
            matchesText && matchesTab
        }.distinctBy { it.kind + ":" + it.recordId }
        countLabel.text = filtered.size.toString() + " سجل"
        if (filtered.isEmpty()) {
            patientList.addView(TextView(this).apply {
                text = if (patients.isEmpty()) "لا توجد بيانات محمّلة. افتح إعداد الاتصال وأدخل عنوان الخادم ورمز المصادقة." else "لا توجد نتائج لهذا التاريخ أو التصنيف."
                textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(20, 34, 20, 34)
                background = rounded(Color.WHITE, 16, border)
            })
            return
        }
        filtered.forEach { patientList.addView(createPatientCard(it)) }
    }

    private fun createPatientCard(item: QueueItem): View {
        val p = item.patient
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(14), dp(12), dp(14)); background = rounded(Color.WHITE, 16, border); elevation = dp(1).toFloat()
        }
        card.addView(TextView(this).apply {
            text = if (p.sex.contains("female", true) || p.sex.contains("أنثى")) "♀" else "♂"
            textSize = 25f; gravity = Gravity.CENTER; setTextColor(blue); background = rounded(Color.rgb(231, 241, 255), 50, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        info.addView(TextView(this).apply { text = p.name; textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        info.addView(TextView(this).apply {
            text = "الملف " + p.fileNo + "  •  " + calculateAge(p.birthDate) + "  •  " + genderLabel(p.sex)
            textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, dp(7))
        })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(this).apply { text = item.time.replace("T", " ").take(16); textSize = 12f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD })
        bottom.addView(TextView(this).apply {
            text = statusLabel(item.status); textSize = 11f; setTextColor(stateColor(item.status)); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(stateBackground(item.status), 30, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        info.addView(bottom)
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply { text = "‹"; textSize = 30f; setTextColor(muted); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        card.setOnClickListener { showPatientDetails(item) }
        card.contentDescription = "فتح ملف " + p.name
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        return card
    }

    private fun showPatientDetails(item: QueueItem) {
        val p = item.patient
        val dialog = Dialog(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(16)); background = rounded(Color.WHITE, 22, Color.TRANSPARENT)
        }
        outer.addView(TextView(this).apply { text = "تفاصيل المريض"; textSize = 20f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD })
        outer.addView(TextView(this).apply { text = p.name + "\nرقم الملف: " + p.fileNo; textSize = 15f; setTextColor(ink); setPadding(0, dp(12), 0, dp(14)) })
        val detailRows = listOf(
            "العمر" to calculateAge(p.birthDate), "الجنس" to genderLabel(p.sex), "الهاتف" to p.phone.ifBlank { "غير مسجل" },
            "الوقت" to item.time, "حالة الزيارة" to statusLabel(item.status), "الطبيب" to item.doctor.ifBlank { "غير محدد" },
            "سبب الزيارة" to item.reason.ifBlank { "غير مسجل" }, "الحساسية" to p.allergies.ifBlank { "غير مسجلة" }
        )
        detailRows.forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply { text = pair.first; textSize = 13f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply { text = pair.second; textSize = 14f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
            outer.addView(row)
        }
        outer.addView(Button(this).apply { text = "إغلاق"; isAllCaps = false; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        dialog.setContentView(outer)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun calculateAge(date: String): String {
        if (date.isBlank() || date == "null") return "العمر غير مسجل"
        return try {
            val birth = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.take(10)) ?: return "العمر غير مسجل"
            val birthCalendar = Calendar.getInstance().apply { time = birth }
            var years = Calendar.getInstance().get(Calendar.YEAR) - birthCalendar.get(Calendar.YEAR)
            val now = Calendar.getInstance()
            if (now.get(Calendar.DAY_OF_YEAR) < birthCalendar.get(Calendar.DAY_OF_YEAR)) years--
            years.coerceAtLeast(0).toString() + " سنة"
        } catch (_: Exception) { "العمر غير مسجل" }
    }

    private fun genderLabel(sex: String) = when (sex.lowercase(Locale.getDefault())) { "male", "m", "ذكر" -> "ذكر"; "female", "f", "أنثى" -> "أنثى"; else -> "غير مسجل" }
    private fun statusLabel(status: String) = when (status) {
        "open" -> "زيارة مفتوحة"; "closed", "completed" -> "مكتملة"; "scheduled" -> "موعد مجدول"; "checked_in" -> "تم الوصول"
        "cancelled" -> "ملغى"; "no_show" -> "لم يحضر"; else -> status.ifBlank { "غير محدد" }
    }
    private fun stateColor(s: String) = when (s) { "completed", "closed" -> Color.rgb(85, 99, 115); "checked_in" -> Color.rgb(25, 125, 66); "open" -> Color.rgb(172, 111, 0); else -> blue }
    private fun stateBackground(s: String) = when (s) { "completed", "closed" -> Color.rgb(239, 242, 246); "checked_in" -> Color.rgb(225, 247, 231); "open" -> Color.rgb(255, 243, 207); else -> Color.rgb(226, 239, 255) }
    private fun rounded(color: Int, radiusDp: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
