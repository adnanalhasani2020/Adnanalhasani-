package com.adnanalhasani.hospital

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
            text = "↔"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(32), dp(48)))
        root.addView(toolbar)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(20))
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
            text = "♙"
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
            text = "العمر: $age    •    الهاتف: $phone"
            textSize = 12f
            setTextColor(muted)
        })
        identity.addView(identityText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(identity, marginBottom())

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 12, border)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("معلومات أساسية", "الزيارة الحالية", "الحالة الطبية").forEachIndexed { index, label ->
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) blue else muted)
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(8), dp(12), dp(8), dp(12))
                if (index == 0) background = rounded(Color.rgb(239, 246, 255), 9, Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(tabs, marginBottom())

        body.addView(sectionTitle("البيانات الشخصية"), marginBottomSmall())
        body.addView(infoCard(listOf(
            "الاسم الكامل" to name,
            "رقم الملف" to fileNo,
            "العمر" to age,
            "رقم الهاتف" to phone,
            "ملاحظات الملف" to notes
        )), marginBottom())

        body.addView(sectionTitle("معلومات الزيارة"), marginBottomSmall())
        body.addView(infoCard(listOf(
            "تاريخ الزيارة" to visitTime,
            "القسم" to "العيادة العامة",
            "العيادة" to "غير محددة",
            "سبب الزيارة" to reason,
            "حالة التحصيل" to "غير محددة",
            "حالة الزيارة" to status,
            "الطبيب المعالج" to doctor
        )), marginBottom())

        body.addView(sectionTitle("إجراءات الطبيب"), marginBottomSmall())
        val actions = listOf(
            "إدخال التشخيص" to "يمكن إضافة التشخيص وملاحظات الطبيب في شاشة التشخيص.",
            "الفحوصات المطلوبة" to "يمكن مراجعة الفحوصات المطلوبة لهذا المريض.",
            "الأدوية المطلوبة" to "يمكن مراجعة الأدوية والوصفات الخاصة بالمريض.",
            "السجل الطبي السابق" to "عرض السجل الطبي السابق للمريض.",
            "موعد المراجعة" to "تحديد موعد مراجعة جديد للمريض."
        )
        actions.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (label, message) ->
                row.addView(Button(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = rounded(blue, 12, Color.TRANSPARENT)
                    setOnClickListener {
                        AlertDialog.Builder(this@PatientDetailsActivity)
                            .setTitle(label)
                            .setMessage(message)
                            .setPositiveButton("حسنًا", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    marginEnd = dp(4)
                    marginStart = dp(4)
                    bottomMargin = dp(8)
                })
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(58), 1f))
            body.addView(row)
        }

        setContentView(root)
    }

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
