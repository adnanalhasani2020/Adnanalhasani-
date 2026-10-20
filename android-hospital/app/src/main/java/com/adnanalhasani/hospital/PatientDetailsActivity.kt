package com.adnanalhasani.hospital

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen patient chart opened when a doctor taps a patient in the queue.
 * All patient information is passed from the existing local SQLite/Room-backed queue.
 */
class PatientDetailsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "patient_id"
        const val EXTRA_NAME = "patient_name"
        const val EXTRA_FILE_NO = "patient_file_no"
        const val EXTRA_AGE = "patient_age"
        const val EXTRA_PHONE = "patient_phone"
        const val EXTRA_NOTES = "patient_notes"
        const val EXTRA_TIME = "visit_time"
        const val EXTRA_STATUS = "visit_status"
        const val EXTRA_DOCTOR = "visit_doctor"
        const val EXTRA_REASON = "visit_reason"
    }

    private val navy = Color.rgb(15, 63, 116)
    private val blue = Color.rgb(21, 101, 192)
    private val ink = Color.rgb(35, 49, 64)
    private val muted = Color.rgb(103, 121, 140)
    private val border = Color.rgb(225, 233, 241)
    private val page = Color.rgb(243, 247, 251)
    private val dao by lazy { HospitalDatabase.get(applicationContext).hospitalDao() }
    private val patientId by lazy { intent.getLongExtra(EXTRA_ID, -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "مريض" }
        val fileNo = intent.getStringExtra(EXTRA_FILE_NO).orEmpty().ifBlank { "غير مرتبط" }
        val age = intent.getStringExtra(EXTRA_AGE).orEmpty().ifBlank { "غير مسجل" }
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty().ifBlank { "غير مسجل" }
        val notes = intent.getStringExtra(EXTRA_NOTES).orEmpty().ifBlank { "لا توجد ملاحظات مسجلة" }
        val visitTime = intent.getStringExtra(EXTRA_TIME).orEmpty().ifBlank { "غير محدد" }
        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "غير محددة" }
        val doctor = intent.getStringExtra(EXTRA_DOCTOR).orEmpty().ifBlank { "غير محدد" }
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "غير مسجل" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(page)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(navy)
        }
        toolbar.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
            contentDescription = "رجوع"
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "تفاصيل المريض"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(TextView(this).apply {
            text = "ملف طبي"
            textSize = 11f
            setTextColor(Color.rgb(220, 235, 255))
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(35, 83, 135), 20, Color.TRANSPARENT)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toolbar)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 18, border)
        }
        identity.addView(TextView(this).apply {
            text = "👤"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(blue)
            background = rounded(Color.rgb(231, 241, 255), 50, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(14) })
        val identityText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identityText.addView(TextView(this).apply {
            text = name
            textSize = 17f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        identityText.addView(TextView(this).apply {
            text = "رقم الملف: $fileNo"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(4))
        })
        identityText.addView(TextView(this).apply {
            text = "العمر: $age"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(2))
        })
        identityText.addView(TextView(this).apply {
            text = "الهاتف: $phone"
            textSize = 12f
            setTextColor(muted)
        })
        identityText.addView(TextView(this).apply {
            text = "●  $status"
            textSize = 11f
            setTextColor(Color.rgb(22, 125, 78))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, 0)
        })
        identity.addView(identityText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(identity, marginBottom())

        val personalSection = sectionTitle("البيانات الشخصية")
        val visitSection = sectionTitle("معلومات الزيارة")
        val actionsSection = sectionTitle("إجراءات الطبيب")

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 12, border)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("البيانات الأساسية", "الزيارة الحالية", "إجراءات الطبيب").forEachIndexed { index, label ->
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) blue else muted)
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(8), dp(12), dp(8), dp(12))
                if (index == 0) background = rounded(Color.rgb(239, 246, 255), 9, Color.TRANSPARENT)
                setOnClickListener {
                    val target = when (index) { 1 -> visitSection; 2 -> actionsSection; else -> personalSection }
                    scroll.post { scroll.smoothScrollTo(0, target.top) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(tabs, marginBottom())

        body.addView(personalSection, marginBottomSmall())
        body.addView(infoCard(listOf(
            "الاسم الكامل" to name,
            "رقم الملف" to fileNo,
            "العمر" to age,
            "رقم الهاتف" to phone,
            "ملاحظات الملف" to notes
        )), marginBottom())

        body.addView(visitSection, marginBottomSmall())
        body.addView(infoCard(listOf(
            "تاريخ الزيارة" to visitTime,
            "القسم" to "العيادة العامة",
            "العيادة" to "غير محددة",
            "سبب الزيارة" to reason,
            "حالة التحصيل" to "غير محددة",
            "حالة الزيارة" to status,
            "الطبيب المعالج" to doctor
        )), marginBottom())

        body.addView(actionsSection, marginBottomSmall())
        val actions = listOf(
            "إدخال التشخيص" to { showEntry("encounters", name, doctor, "إدخال التشخيص", listOf("الشكوى الرئيسية", "التشخيص", "ملاحظات"), visitTime) },
            "الفحوصات المطلوبة" to { showEntry("lab", name, doctor, "طلب فحص مخبري", listOf("اسم الفحص", "حالة الفحص", "النتيجة", "ملاحظات المختبر"), visitTime) },
            "الأدوية المطلوبة" to { showEntry("prescriptions", name, doctor, "وصفة دوائية", listOf("اسم الدواء", "الجرعة", "التكرار والمدة", "الحالة", "تعليمات إضافية"), visitTime) },
            "السجل الطبي السابق" to { showHistory(name) },
            "موعد المراجعة" to { showAppointmentDialog(name, doctor, visitTime) },
            "مواعيد المراجعة السابقة" to { showAppointments(name) }
        )
        actions.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (label, action) ->
                row.addView(Button(this).apply {
                    text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE)
                    background = rounded(blue, 12, Color.TRANSPARENT)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(4); marginStart = dp(4); bottomMargin = dp(8) })
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(58), 1f))
            body.addView(row)
        }

        setContentView(root)
    }


    private fun showEntry(category: String, patient: String, doctor: String, title: String, labels: List<String>, visitTime: String) {
        val inputs = labels.map { label ->
            label to EditText(this).apply { hint = label; if (category == "lab" && label == "حالة الفحص") setText("مطلوب"); if (category == "prescriptions" && label == "الحالة") setText("معلقة"); if (label.contains("ملاحظات") || label.contains("تعليمات")) minLines = 2 }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        inputs.forEach { (label, edit) ->
            form.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(muted); setPadding(0, dp(7), 0, dp(3)) })
            edit.setPadding(dp(12), dp(10), dp(12), dp(10)); edit.background = rounded(Color.WHITE, 10, border)
            form.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setNegativeButton("إلغاء", null).setPositiveButton("حفظ", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = inputs.map { it.first to it.second.text.toString().trim() }
                if (values.firstOrNull()?.second.isNullOrBlank()) { inputs.first().second.error = "هذا الحقل مطلوب"; return@setOnClickListener }
                if (category == "prescriptions") {
                    val doseIndex = labels.indexOf("الجرعة")
                    val frequencyIndex = labels.indexOf("التكرار والمدة")
                    if (doseIndex >= 0 && values[doseIndex].second.isBlank()) { inputs[doseIndex].second.error = "الجرعة مطلوبة"; return@setOnClickListener }
                    if (frequencyIndex >= 0 && values[frequencyIndex].second.isBlank()) { inputs[frequencyIndex].second.error = "التكرار والمدة مطلوبان"; return@setOnClickListener }
                }
                val detail = buildString {
                    append("اسم المريض: $patient\nاسم الطبيب: $doctor\n")
                    values.forEach { (key, value) -> append("$key: $value\n") }
                    append("تاريخ الزيارة: $visitTime")
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dao.saveRecord(HospitalRecord(category = category, title = "$title - $patient", details = detail, createdAt = System.currentTimeMillis()), linkedPatientId = patientId.takeIf { it > 0 })
                        runOnUiThread { dialog.dismiss(); toast("تم الحفظ في SQLite المحلية") }
                    } catch (e: Exception) { runOnUiThread { toast("تعذر الحفظ: ${e.message ?: "خطأ"}") } }
                }
            }
        }
        dialog.show()
    }

    private fun showAppointmentDialog(patient: String, doctor: String, currentVisitTime: String) {
        val now = java.util.Calendar.getInstance()
        val selectedDate = java.util.Calendar.getInstance()
        val selectedTime = java.util.Calendar.getInstance()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun addLabel(text: String) {
            form.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        fun addReadOnlyValue(value: String): TextView {
            val view = TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.rgb(245, 248, 252), 10, border)
            }
            form.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return view
        }

        addLabel("المريض")
        addReadOnlyValue(patient)
        addLabel("الطبيب المعالج")
        addReadOnlyValue(doctor)
        addLabel("تاريخ المراجعة")
        val dateInput = Button(this).apply {
            text = dateFormat.format(selectedDate.time)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(Color.WHITE, 10, border)
            setOnClickListener {
                DatePickerDialog(this@PatientDetailsActivity,
                    { _, year, month, day ->
                        selectedDate.set(year, month, day)
                        text = dateFormat.format(selectedDate.time)
                    },
                    selectedDate.get(java.util.Calendar.YEAR),
                    selectedDate.get(java.util.Calendar.MONTH),
                    selectedDate.get(java.util.Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        form.addView(dateInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        addLabel("وقت المراجعة")
        val timeInput = Button(this).apply {
            text = timeFormat.format(selectedTime.time)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(Color.WHITE, 10, border)
            setOnClickListener {
                TimePickerDialog(this@PatientDetailsActivity,
                    { _, hour, minute ->
                        selectedTime.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        selectedTime.set(java.util.Calendar.MINUTE, minute)
                        text = timeFormat.format(selectedTime.time)
                    },
                    selectedTime.get(java.util.Calendar.HOUR_OF_DAY),
                    selectedTime.get(java.util.Calendar.MINUTE),
                    true
                ).show()
            }
        }
        form.addView(timeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        addLabel("سبب المراجعة")
        val reasonInput = EditText(this).apply {
            hint = "اكتب سبب المراجعة"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, 10, border)
            minLines = 2
        }
        form.addView(reasonInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addLabel("ملاحظات إضافية")
        val notesInput = EditText(this).apply {
            hint = "اختياري"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, 10, border)
            minLines = 2
        }
        form.addView(notesInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle("تحديد موعد المراجعة")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ الموعد", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = reasonInput.text.toString().trim()
                if (reason.isBlank()) {
                    reasonInput.error = "سبب المراجعة مطلوب"
                    return@setOnClickListener
                }
                val appointmentAt = "${dateFormat.format(selectedDate.time)} ${timeFormat.format(selectedTime.time)}"
                val detail = buildString {
                    append("اسم المريض: $patient\n")
                    append("اسم الطبيب: $doctor\n")
                    append("التاريخ والوقت: $appointmentAt\n")
                    append("سبب الزيارة: $reason\n")
                    append("حالة الموعد: مجدول\n")
                    append("تاريخ الزيارة السابقة: $currentVisitTime\n")
                    append("ملاحظات: ${notesInput.text.toString().trim()}")
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dao.saveRecord(
                            HospitalRecord(
                                category = "appointments",
                                title = "موعد مراجعة - $patient",
                                details = detail,
                                createdAt = System.currentTimeMillis()
                            ),
                            linkedPatientId = patientId.takeIf { it > 0 }
                        )
                        runOnUiThread {
                            dialog.dismiss()
                            toast("تم حفظ موعد المراجعة في SQLite")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("تعذر حفظ الموعد: ${e.message ?: "خطأ"}") }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showAppointments(patient: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appointments = dao.appointmentsForPatient(patientId, patient).first()
                runOnUiThread {
                    if (appointments.isEmpty()) {
                        AlertDialog.Builder(this@PatientDetailsActivity)
                            .setTitle("مواعيد المراجعة السابقة")
                            .setMessage("لا توجد مواعيد مراجعة مسجلة لهذا المريض.")
                            .setPositiveButton("إغلاق", null)
                            .show()
                        return@runOnUiThread
                    }
                    val labels = appointments.map { appointment ->
                        "${appointment.appointmentAt.ifBlank { "تاريخ غير محدد" }} • ${appointment.reason.ifBlank { "بدون سبب" }} • ${appointment.status.ifBlank { "مجدول" }}"
                    }.toTypedArray()
                    AlertDialog.Builder(this@PatientDetailsActivity)
                        .setTitle("اختر موعدًا لإدارته")
                        .setItems(labels) { _, index ->
                            val selected = appointments[index]
                            AlertDialog.Builder(this@PatientDetailsActivity)
                                .setTitle("موعد المراجعة")
                                .setItems(arrayOf("تعديل الموعد", "إلغاء الموعد", "رجوع")) { _, action ->
                                    when (action) {
                                        0 -> showEditAppointmentDialog(selected.id, selected.appointmentAt, selected.reason, selected.status)
                                        1 -> confirmCancelAppointment(selected.id, selected.appointmentAt)
                                    }
                                }
                                .show()
                        }
                        .setNegativeButton("إغلاق", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("تعذر تحميل المواعيد: ${e.message ?: "خطأ"}") }
            }
        }
    }

    private fun showEditAppointmentDialog(id: Long, appointmentAt: String, reason: String, status: String) {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val calendar = java.util.Calendar.getInstance()
        try {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).parse(appointmentAt)
            if (parsed != null) calendar.time = parsed
        } catch (_: Exception) { }

        val dateCalendar = calendar.clone() as java.util.Calendar
        val timeCalendar = calendar.clone() as java.util.Calendar
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dateButton = Button(this).apply {
            text = dateFormat.format(dateCalendar.time)
            isAllCaps = false
            setOnClickListener {
                DatePickerDialog(this@PatientDetailsActivity, { _, year, month, day ->
                    dateCalendar.set(year, month, day)
                    text = dateFormat.format(dateCalendar.time)
                }, dateCalendar.get(java.util.Calendar.YEAR), dateCalendar.get(java.util.Calendar.MONTH), dateCalendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
        }
        val timeButton = Button(this).apply {
            text = timeFormat.format(timeCalendar.time)
            isAllCaps = false
            setOnClickListener {
                TimePickerDialog(this@PatientDetailsActivity, { _, hour, minute ->
                    timeCalendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                    timeCalendar.set(java.util.Calendar.MINUTE, minute)
                    text = timeFormat.format(timeCalendar.time)
                }, timeCalendar.get(java.util.Calendar.HOUR_OF_DAY), timeCalendar.get(java.util.Calendar.MINUTE), true).show()
            }
        }
        val reasonInput = EditText(this).apply {
            setText(reason)
            hint = "سبب المراجعة"
            minLines = 2
        }
        listOf("تاريخ المراجعة" to dateButton, "وقت المراجعة" to timeButton, "سبب المراجعة" to reasonInput).forEach { (label, view) ->
            form.addView(TextView(this).apply {
                text = label
                setTextColor(muted)
                textSize = 13f
                setPadding(0, dp(8), 0, dp(4))
            })
            view.setPadding(dp(12), dp(10), dp(12), dp(10))
            view.background = rounded(Color.WHITE, 10, border)
            form.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("تعديل موعد المراجعة")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ التعديل", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newReason = reasonInput.text.toString().trim()
                if (newReason.isBlank()) {
                    reasonInput.error = "سبب المراجعة مطلوب"
                    return@setOnClickListener
                }
                val newDateTime = "${dateFormat.format(dateCalendar.time)} ${timeFormat.format(timeCalendar.time)}"
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dao.updateAppointmentById(id, newDateTime, newReason, status.ifBlank { "مجدول" })
                        runOnUiThread {
                            dialog.dismiss()
                            toast("تم تحديث الموعد")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("تعذر تحديث الموعد: ${e.message ?: "خطأ"}") }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmCancelAppointment(id: Long, appointmentAt: String) {
        AlertDialog.Builder(this)
            .setTitle("إلغاء موعد المراجعة")
            .setMessage("هل تريد إلغاء الموعد بتاريخ $appointmentAt؟")
            .setNegativeButton("رجوع", null)
            .setPositiveButton("تأكيد الإلغاء") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        dao.updateAppointmentStatus(id, "ملغى")
                        runOnUiThread { toast("تم إلغاء الموعد") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("تعذر إلغاء الموعد: ${e.message ?: "خطأ"}") }
                    }
                }
            }
            .show()
    }

    private fun showHistory(patient: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = listOf("encounters", "lab", "radiology", "prescriptions", "appointments")
                    .flatMap { dao.records(it).first() }
                    .filter { it.details.contains("اسم المريض: $patient") || it.title.contains(patient) }
                    .sortedByDescending { it.createdAt }
                val message = if (records.isEmpty()) "لا يوجد سجل سابق لهذا المريض." else records.joinToString("\n\n") {
                    "${it.category} • ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.createdAt))}\n${it.title}\n${it.details}"
                }
                runOnUiThread {
                    val text = TextView(this@PatientDetailsActivity).apply { this.text = message; textSize = 14f; setTextColor(ink); setPadding(dp(16), dp(12), dp(16), dp(12)) }
                    AlertDialog.Builder(this@PatientDetailsActivity).setTitle("السجل الطبي السابق")
                        .setView(ScrollView(this@PatientDetailsActivity).apply { addView(text) })
                        .setPositiveButton("إغلاق", null).show()
                }
            } catch (e: Exception) { runOnUiThread { toast("تعذر تحميل السجل: ${e.message ?: "خطأ"}") } }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(navy)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun infoCard(rows: List<Pair<String, String>>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = rounded(Color.WHITE, 16, border)
        }
        rows.forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(11), 0, dp(11))
            }
            row.addView(TextView(this).apply {
                text = pair.first
                textSize = 13f
                setTextColor(muted)
            }, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = pair.second
                textSize = 14f
                setTextColor(ink)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
                maxWidth = dp(220)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            if (index != rows.lastIndex) card.addView(View(this).apply { setBackgroundColor(border) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        return card
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun marginBottom() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun marginBottomSmall() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(4) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
