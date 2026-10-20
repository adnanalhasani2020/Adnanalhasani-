package com.adnanalhasani.hospital

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
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class DoctorWorkflowActivity : AppCompatActivity() {
    private data class Patient(val id: Long, val fileNo: String, val name: String, val age: String, val phone: String, val notes: String)
    private data class QueueItem(val patient: Patient, val time: String, val status: String, val kind: String, val recordId: Long, val reason: String, val doctor: String)
    private lateinit var dao: HospitalDao
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
        setContentView(R.layout.activity_doctor_workflow)
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
        connectionLabel = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(dp(14), dp(6), dp(14), dp(6)); text = "قاعدة البيانات المحلية على هذا الجهاز" }
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
        findViewById<TextView>(R.id.configureConnection).apply {
            text = "↻ تحديث البيانات المحلية"
            setOnClickListener { loadQueue() }
        }
        dao = HospitalDatabase.get(applicationContext).hospitalDao()
        updateDateLabel()
        styleTabButtons()
        renderPatients()
        loadQueue()
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

    /**
     * Reads the same Room/SQLite database used by the rest of the app.
     * The doctor's screen does not call an external API or use a separate DB.
     */
    private fun loadQueue() {
        connectionLabel.text = "جارٍ قراءة قاعدة البيانات المحلية..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DemoDataSeeder.seedIfEmpty(dao)
                val patientRows = dao.patients().first()
                val appointmentRows = dao.appointments().first()
                val visitRows = dao.clinicalVisits().first()
                val pmap = patientRows.associate { row ->
                    row.id to Patient(row.id, row.fileNumber, row.fullName, row.age, row.phone, row.notes)
                }
                val rows = mutableListOf<QueueItem>()
                appointmentRows.forEach { appointment ->
                    val patient = appointment.patientId?.let { pmap[it] }
                        ?: patientRows.firstOrNull { it.fullName.equals(appointment.patientName, true) }?.let { pmap[it.id] }
                        ?: Patient(-appointment.createdAt, "", appointment.patientName.ifBlank { "مريض غير مرتبط بملف" }, "", "", "")
                    rows.add(QueueItem(
                        patient, appointment.appointmentAt.ifBlank { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(appointment.createdAt)) },
                        appointment.status, "appointment", appointment.id, appointment.reason, appointment.doctorName
                    ))
                }
                visitRows.forEach { visit ->
                    val patient = visit.patientId?.let { pmap[it] }
                        ?: patientRows.firstOrNull { it.fullName.equals(visit.patientName, true) }?.let { pmap[it.id] }
                        ?: Patient(-visit.sourceRecordId, "", visit.patientName.ifBlank { "مريض غير مرتبط بملف" }, "", "", "")
                    rows.add(QueueItem(
                        patient, visit.visitAt.ifBlank { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(visit.createdAt)) },
                        "completed", "encounter", visit.id,
                        listOf(visit.chiefComplaint, visit.diagnosis, visit.notes).filter { it.isNotBlank() }.joinToString(" • "),
                        visit.doctorName
                    ))
                }
                val linkedPatientIds = rows.filter { it.status !in setOf("cancelled", "no_show") }.map { it.patient.id }.toSet()
                patientRows.filter { it.id !in linkedPatientIds }.forEach { row ->
                    rows.add(QueueItem(
                        pmap.getValue(row.id),
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(row.createdAt)),
                        "open", "patient", row.id, row.notes, ""
                    ))
                }
                patients = pmap.values.toList()
                queueItems = rows.sortedByDescending { it.time }
                runOnUiThread {
                    connectionLabel.text = "بيانات محلية • ${patients.size} مريض • ${appointmentRows.size} موعد • ${visitRows.size} زيارة"
                    renderPatients()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectionLabel.text = "تعذر قراءة قاعدة البيانات المحلية"
                    toast("تعذر تحميل البيانات المحلية: " + (e.message ?: "خطأ غير معروف"))
                    renderPatients()
                }
            }
        }
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
                "completed" -> item.kind == "encounter" || status == "completed" || status == "closed"
                "upcoming" -> item.kind == "appointment" && (status == "scheduled" || status == "checked_in") && date >= day
                else -> item.kind == "patient" || (status != "completed" && status != "closed" && status != "cancelled" && status != "no_show" && date.startsWith(day))
            }
            matchesText && matchesTab
        }.distinctBy { it.kind + ":" + it.recordId }
        countLabel.text = filtered.size.toString() + " سجل"
        if (filtered.isEmpty()) {
            patientList.addView(TextView(this).apply {
                text = if (patients.isEmpty() && queueItems.isEmpty()) "لا توجد بيانات مرضى في قاعدة البيانات المحلية على هذا الجهاز." else "لا توجد نتائج لهذا التاريخ أو التصنيف."
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
            text = "♙"
            textSize = 25f; gravity = Gravity.CENTER; setTextColor(blue); background = rounded(Color.rgb(231, 241, 255), 50, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        info.addView(TextView(this).apply { text = p.name; textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        info.addView(TextView(this).apply {
            text = "الملف " + p.fileNo.ifBlank { "غير مرتبط" } + "  •  " + p.age.ifBlank { "العمر غير مسجل" }
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
        startActivity(android.content.Intent(this, PatientDetailsActivity::class.java).apply {
            putExtra(PatientDetailsActivity.EXTRA_ID, p.id)
            putExtra(PatientDetailsActivity.EXTRA_NAME, p.name)
            putExtra(PatientDetailsActivity.EXTRA_FILE_NO, p.fileNo)
            putExtra(PatientDetailsActivity.EXTRA_AGE, p.age)
            putExtra(PatientDetailsActivity.EXTRA_PHONE, p.phone)
            putExtra(PatientDetailsActivity.EXTRA_NOTES, p.notes)
            putExtra(PatientDetailsActivity.EXTRA_TIME, item.time)
            putExtra(PatientDetailsActivity.EXTRA_STATUS, statusLabel(item.status))
            putExtra(PatientDetailsActivity.EXTRA_DOCTOR, item.doctor)
            putExtra(PatientDetailsActivity.EXTRA_REASON, item.reason)
        })
    }

    private fun statusLabel(status: String) = when (status) {
        "open" -> "ملف مريض"; "closed", "completed" -> "مكتملة"; "scheduled" -> "موعد مجدول"; "checked_in" -> "تم الوصول"
        "cancelled" -> "ملغى"; "no_show" -> "لم يحضر"; else -> status.ifBlank { "غير محدد" }
    }
    private fun stateColor(s: String) = when (s) { "completed", "closed" -> Color.rgb(85, 99, 115); "checked_in" -> Color.rgb(25, 125, 66); "open" -> Color.rgb(172, 111, 0); else -> blue }
    private fun stateBackground(s: String) = when (s) { "completed", "closed" -> Color.rgb(239, 242, 246); "checked_in" -> Color.rgb(225, 247, 231); "open" -> Color.rgb(255, 243, 207); else -> Color.rgb(226, 239, 255) }
    private fun rounded(color: Int, radiusDp: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

}
