package com.hospital.doctor

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private data class Patient(val time: String, val name: String, val fileNo: String, val detail: String, val state: String)
    private val samplePatients = listOf(
        Patient("09:00", "محمد علي حسن", "10234", "32 سنة • ذكر", "في الانتظار"),
        Patient("09:30", "أسماء عبدالله صالح", "10418", "28 سنة • أنثى", "تم الدخول"),
        Patient("10:00", "سالم ناصر محمد", "10321", "45 سنة • ذكر", "جاري الكشف"),
        Patient("10:30", "فاطمة علي أحمد", "10287", "36 سنة • أنثى", "مكتمل"),
        Patient("11:00", "خالد سعيد عبدالله", "10456", "52 سنة • ذكر", "في الانتظار"),
        Patient("11:30", "نورة محمد صالح", "10478", "26 سنة • أنثى", "في الانتظار")
    )
    private var activeTab = "today"
    private lateinit var patientList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var listTitle: TextView
    private lateinit var dateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        patientList = findViewById(R.id.patientList)
        searchInput = findViewById(R.id.searchInput)
        listTitle = findViewById(R.id.listTitle)
        dateButton = findViewById(R.id.dateButton)
        findViewById<Button>(R.id.tabToday).setOnClickListener { selectTab("today") }
        findViewById<Button>(R.id.tabUpcoming).setOnClickListener { selectTab("upcoming") }
        findViewById<Button>(R.id.tabCompleted).setOnClickListener { selectTab("completed") }
        dateButton.setOnClickListener { showDatePicker() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderPatients()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateDateLabel()
        renderPatients()
    }

    private fun selectTab(tab: String) {
        activeTab = tab
        listTitle.text = when (tab) {
            "upcoming" -> "المواعيد القادمة"
            "completed" -> "الزيارات المنتهية"
            else -> "المرضى المنتظرون"
        }
        renderPatients()
    }

    private fun showDatePicker() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val picked = Calendar.getInstance().apply { set(year, month, day) }
            dateButton.text = "📅  " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(picked.time)
            Toast.makeText(this, "تم اختيار التاريخ. ربط القائمة بواجهة API لعرض بيانات ذلك اليوم ما زال مطلوبًا.", Toast.LENGTH_LONG).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateDateLabel() {
        dateButton.text = "📅  " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
    }

    private fun renderPatients() {
        patientList.removeAllViews()
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = samplePatients.filter { patient ->
            val matchesSearch = (patient.name + " " + patient.fileNo + " " + patient.detail).lowercase(Locale.getDefault()).contains(query)
            val matchesTab = when (activeTab) {
                "completed" -> patient.state == "مكتمل"
                "upcoming" -> patient.state != "مكتمل"
                else -> patient.state != "مكتمل"
            }
            matchesSearch && matchesTab
        }
        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text = "لا توجد نتائج مطابقة"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(20, 32, 20, 32)
            }
            patientList.addView(empty)
            return
        }
        filtered.forEach { patient -> patientList.addView(createPatientCard(patient)) }
    }

    private fun createPatientCard(patient: Patient): TextView {
        return TextView(this).apply {
            text = "◉   " + patient.time + "     " + patient.name + "\n       الملف: " + patient.fileNo + "   •   " + patient.detail + "\n       الحالة: " + patient.state + "     ›"
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(26, 47, 67))
            setPadding(18, 18, 18, 18)
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 30f
                setStroke(1, android.graphics.Color.rgb(226, 234, 241))
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = 12
            layoutParams = params
            setOnClickListener {
                Toast.makeText(this@MainActivity, "تم اختيار " + patient.name + ". شاشة التفاصيل وربط API ما زالا مطلوبين.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
