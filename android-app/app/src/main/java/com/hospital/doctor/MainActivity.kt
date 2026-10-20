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
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private data class Patient(val time: String, val name: String, val fileNo: String, val age: Int, val gender: String, val phone: String, val reason: String, val state: String)
    // Sample-only records; do not use as real clinical data.
    private val demoPatients = listOf(
        Patient("09:00", "محمد علي حسن", "10234", 32, "ذكر", "0771234567", "ألم في البطن", "في الانتظار"),
        Patient("09:30", "أسماء عبدالله صالح", "10418", 28, "أنثى", "0771234568", "مراجعة عامة", "تم الدخول"),
        Patient("10:00", "سالم ناصر محمد", "10321", 45, "ذكر", "0771234569", "متابعة ضغط الدم", "جاري الكشف"),
        Patient("10:30", "فاطمة علي أحمد", "10287", 36, "أنثى", "0771234570", "زيارة متابعة", "مكتمل"),
        Patient("11:00", "خالد سعيد عبدالله", "10456", 52, "ذكر", "0771234571", "استشارة عامة", "في الانتظار"),
        Patient("11:30", "نورة محمد صالح", "10478", 26, "أنثى", "0771234572", "متابعة", "في الانتظار")
    )
    private var activeTab = "today"
    private lateinit var patientList: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var listTitle: TextView
    private lateinit var dateButton: Button
    private lateinit var countLabel: TextView
    private var selectedDate = Calendar.getInstance()
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
        (listTitle.parent as? ViewGroup)?.let { it.addView(countLabel, it.indexOfChild(listTitle) + 1) }
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
        styleTabButtons()
        renderPatients()
    }

    private fun selectTab(tab: String) {
        activeTab = tab
        listTitle.text = when (tab) { "upcoming" -> "المواعيد القادمة"; "completed" -> "الزيارات المنتهية"; else -> "المرضى المنتظرون" }
        styleTabButtons()
        renderPatients()
    }

    private fun styleTabButtons() {
        listOf(R.id.tabToday to "today", R.id.tabUpcoming to "upcoming", R.id.tabCompleted to "completed").forEach { (id, tab) ->
            val button = findViewById<Button>(id)
            val active = activeTab == tab
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
            Toast.makeText(this, "واجهة تجريبية: اختيار التاريخ لا يجلب بيانات الخادم بعد.", Toast.LENGTH_LONG).show()
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateDateLabel() {
        dateButton.text = "▦   " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(selectedDate.time) + "     اختيار التاريخ"
        dateButton.isAllCaps = false
        dateButton.textSize = 14f
        dateButton.setTextColor(navy)
        dateButton.background = rounded(Color.WHITE, 12, border)
    }

    private fun renderPatients() {
        patientList.removeAllViews()
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = demoPatients.filter { p ->
            val hay = (p.name + " " + p.fileNo + " " + p.phone).lowercase(Locale.getDefault())
            val tabMatches = when (activeTab) { "completed" -> p.state == "مكتمل"; "upcoming" -> p.state != "مكتمل"; else -> p.state != "مكتمل" }
            hay.contains(query) && tabMatches
        }
        countLabel.text = filtered.size.toString() + " مرضى"
        if (filtered.isEmpty()) {
            patientList.addView(TextView(this).apply {
                text = "لا توجد نتائج مطابقة\nجرّب اسمًا آخر أو رقم ملف مختلفًا."
                textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(20, 34, 20, 34)
                background = rounded(Color.WHITE, 16, border)
            })
            return
        }
        filtered.forEach { patientList.addView(createPatientCard(it)) }
    }

    private fun createPatientCard(p: Patient): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(14), dp(12), dp(14)); background = rounded(Color.WHITE, 16, border); elevation = dp(1).toFloat()
        }
        card.addView(TextView(this).apply {
            text = if (p.gender == "أنثى") "♀" else "♂"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(blue)
            background = rounded(Color.rgb(231, 241, 255), 50, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        info.addView(TextView(this).apply { text = p.name; textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        info.addView(TextView(this).apply {
            text = "الملف " + p.fileNo + "  •  " + p.age + " سنة  •  " + p.gender
            textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, dp(7))
        })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(TextView(this).apply { text = p.time; textSize = 12f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD })
        bottom.addView(TextView(this).apply {
            text = p.state; textSize = 11f; setTextColor(stateColor(p.state)); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(stateBackground(p.state), 30, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        info.addView(bottom)
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply { text = "‹"; textSize = 30f; setTextColor(muted); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        card.setOnClickListener { showPatientDetails(p) }
        card.contentDescription = "فتح ملف " + p.name
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        return card
    }

    private fun showPatientDetails(p: Patient) {
        val dialog = Dialog(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(16)); background = rounded(Color.WHITE, 22, Color.TRANSPARENT)
        }
        outer.addView(TextView(this).apply { text = "تفاصيل المريض"; textSize = 20f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD })
        outer.addView(TextView(this).apply {
            text = p.name + "\nرقم الملف: " + p.fileNo; textSize = 15f; setTextColor(ink); setPadding(0, dp(12), 0, dp(14))
        })
        listOf("العمر" to (p.age.toString() + " سنة"), "الجنس" to p.gender, "الهاتف" to p.phone, "وقت الموعد" to p.time, "حالة الزيارة" to p.state, "سبب الزيارة" to p.reason).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply { text = pair.first; textSize = 13f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply { text = pair.second; textSize = 14f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
            outer.addView(row)
        }
        outer.addView(TextView(this).apply {
            text = "وضع تجريبي: هذه أمثلة محلية وليست سجلات من قاعدة بيانات المستشفى."
            textSize = 12f; setTextColor(Color.rgb(140, 83, 0)); setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(255, 246, 222), 10, Color.TRANSPARENT)
        })
        outer.addView(Button(this).apply { text = "إغلاق"; isAllCaps = false; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        dialog.setContentView(outer)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun stateColor(s: String) = when (s) { "مكتمل" -> Color.rgb(85, 99, 115); "جاري الكشف" -> Color.rgb(172, 111, 0); "تم الدخول" -> Color.rgb(25, 125, 66); else -> blue }
    private fun stateBackground(s: String) = when (s) { "مكتمل" -> Color.rgb(239, 242, 246); "جاري الكشف" -> Color.rgb(255, 243, 207); "تم الدخول" -> Color.rgb(225, 247, 231); else -> Color.rgb(226, 239, 255) }
    private fun rounded(color: Int, radiusDp: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
