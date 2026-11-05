package com.adnanalhasani.hospital

import android.os.Bundle
import android.content.Intent
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF263B4D)
private val Teal = Color(0xFF548F88)
private val Mint = Color(0xFFE5F1EE)
private val Canvas = Color(0xFFF6F8FA)
private val Ink = Color(0xFF293B49)
private val Muted = Color(0xFF788895)
private val Amber = Color(0xFFD5AD6B)
private val Rose = Color(0xFFC97979)
private val Border = Color(0xFFE4EAEE)
private val SoftBlue = Color(0xFF7897B7)
private val SoftLavender = Color(0xFF9A91B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = HospitalDatabase.get(this)
        val repository = HospitalRepository(database.hospitalDao(), database)
        if (BuildConfig.DEBUG) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DemoDataSeeder.seedIfEmpty(database.hospitalDao()) }
            }
        }
        setContent { HospitalEntry(repository) }
    }
}

data class Module(
    val key: String, val title: String, val subtitle: String,
    val icon: ImageVector, val color: Color,
    val fields: List<Pair<String, String>>
)

private val modules = listOf(
    Module("patients", "المرضى", "ملفات المرضى وبيانات التواصل", Icons.Default.Groups, Color(0xFF548F88),
        listOf("رقم الملف" to "مثال: PT-1001", "اسم المريض" to "الاسم الكامل", "رقم الهاتف" to "رقم التواصل", "العمر" to "العمر", "ملاحظات" to "ملاحظات إدارية")),
    Module("doctors", "الأطباء", "الطاقم الطبي والتخصصات", Icons.Default.MedicalServices, Color(0xFF7897B7),
        listOf("الرقم الوظيفي" to "مثال: DR-101", "اسم الطبيب" to "الاسم الكامل", "التخصص" to "التخصص الطبي", "رقم الهاتف" to "رقم التواصل")),
    Module("doctor_workspace", "شاشة الطبيب", "تسجيل الزيارات والتشخيصات ومراجعة السجل الطبي", Icons.Default.MedicalInformation, Color(0xFF6F9BAE), emptyList()),
    Module("appointments", "المواعيد", "تنظيم المواعيد والحجوزات", Icons.Default.CalendarMonth, Color(0xFF9A91B8),
        listOf("اسم المريض" to "اسم المريض", "اسم الطبيب" to "اسم الطبيب", "التاريخ والوقت" to "البداية: YYYY-MM-DD HH:mm", "وقت الانتهاء" to "النهاية: YYYY-MM-DD HH:mm", "سبب الزيارة" to "سبب الزيارة", "حالة الموعد" to "مجدول / حضر / مكتمل / ملغي / لم يحضر")),
    Module("encounters", "الزيارات الطبية", "متابعة الزيارة والتشخيص", Icons.Default.MedicalInformation, Color(0xFF6F9BAE),
        listOf("اسم المريض" to "اسم المريض", "اسم الطبيب" to "اسم الطبيب", "تاريخ الزيارة" to "YYYY-MM-DD HH:mm", "الشكوى الرئيسية" to "الشكوى", "التشخيص" to "التشخيص الطبي", "ملاحظات" to "ملاحظات الزيارة", "حالة الزيارة" to "مفتوحة / مغلقة")),
    Module("lab", "المختبر", "إدارة طلبات التحاليل ومتابعة النتائج", Icons.Default.Science, Color(0xFFD0A06E),
        listOf(
            "اسم المريض" to "ابحث عن اسم المريض أو أدخله",
            "معرّف الزيارة" to "رقم الزيارة من الملف الطبي (اختياري)",
            "اسم الفحص" to "اختر الفحص من القائمة أو اكتب اسمه",
            "حالة الفحص" to "مطلوب / قيد التنفيذ / مكتمل",
            "النتيجة" to "أدخل النتيجة أو اتركها فارغة حتى صدورها",
            "ملاحظات المختبر" to "العينة، تعليمات التحضير، أو ملاحظات إضافية"
        )),
    Module("pharmacy", "الصيدلية والأدوية", "الأدوية والوصفات والمخزون", Icons.Default.Medication, Color(0xFF6D9D86),
        listOf("اسم الدواء" to "اسم الدواء", "رمز الدواء" to "رمز أو SKU", "الكمية" to "كمية التوريد", "رقم التشغيلة" to "رقم دفعة الدواء", "تاريخ الصلاحية" to "YYYY-MM-DD", "المرجع" to "رقم التوريد أو المورد", "حد إعادة الطلب" to "الحد الأدنى للمخزون", "وحدة القياس" to "علبة / شريط / قطعة")),
    Module("admissions", "التنويم والأسرة", "إدارة الأسرة والدخول والخروج", Icons.Default.Hotel, Color(0xFF6D82C4),
        listOf("اسم المريض" to "اسم المريض", "القسم / الجناح" to "القسم", "رقم السرير" to "رقم السرير", "حالة التنويم" to "منوّم / خرج", "التاريخ" to "تاريخ الدخول أو الخروج")),
    Module("invoices", "الفواتير", "إنشاء ومتابعة الفواتير", Icons.Default.ReceiptLong, Color(0xFFD0A06E),
        listOf("رقم الفاتورة" to "رقم الفاتورة", "اسم المريض" to "اسم المريض", "المبلغ" to "المبلغ", "العملة" to "YER", "الحالة" to "مفتوحة / مدفوعة")),
    Module("payments", "المدفوعات", "تسجيل الدفعات والإيصالات", Icons.Default.Payments, Color(0xFF548F88),
        listOf("رقم الفاتورة" to "رقم الفاتورة", "المبلغ المدفوع" to "المبلغ", "طريقة الدفع" to "نقداً / تحويل", "مرجع الإيصال" to "رقم الإيصال")),
    Module("prescriptions", "الوصفات الطبية", "وصفات المرضى وتعليمات الدواء", Icons.Default.Description, Color(0xFFBD6D9D),
        listOf("اسم المريض" to "اسم المريض", "اسم الطبيب" to "اسم الطبيب", "معرّف الزيارة" to "رقم الزيارة من الملف الطبي (اختياري)", "اسم الدواء" to "اسم الدواء", "الجرعة" to "الجرعة", "التكرار والمدة" to "التكرار والمدة", "الكمية المطلوبة" to "عدد الوحدات التي ستُصرف", "الحالة" to "معلقة / صُرفت", "تعليمات إضافية" to "تعليمات الدواء")),
    Module("beds", "إدارة الأسرة", "تسجيل الأسرة وحالتها", Icons.Default.Hotel, Color(0xFF5986A4),
        listOf("الجناح" to "اسم الجناح", "رقم السرير" to "رقم السرير", "الحالة" to "متاح / مشغول", "ملاحظات" to "ملاحظات")),
    Module("users", "المستخدمون", "سجل إداري محلي للمستخدمين", Icons.Default.AdminPanelSettings, Color(0xFF7C78C9),
        listOf("اسم المستخدم" to "اسم المستخدم", "الدور" to "مدير / طبيب / استقبال", "الحالة" to "نشط / غير نشط")),
    Module("nursing", "التمريض والعلامات الحيوية", "متابعة القياسات والرعاية التمريضية", Icons.Default.MedicalInformation, Color(0xFF277DA1),
        listOf("اسم المريض" to "اسم المريض", "الضغط" to "مثال: 120/80", "الحرارة" to "درجة الحرارة", "النبض" to "نبضة/دقيقة", "التنفس" to "نفس/دقيقة", "ملاحظات التمريض" to "ملاحظات")),
    Module("radiology", "الأشعة والتصوير", "طلبات الأشعة وتقارير النتائج", Icons.Default.Science, Color(0xFF6C63A8),
        listOf("اسم المريض" to "اسم المريض", "معرّف الزيارة" to "رقم الزيارة من الملف الطبي (اختياري)", "نوع التصوير" to "أشعة / CT / MRI / موجات فوق صوتية", "الطبيب الطالب" to "اسم الطبيب", "الحالة" to "مطلوب / قيد التنفيذ / مكتمل", "التقرير" to "النتيجة أو التقرير")),
    Module("operations", "العمليات والإجراءات", "جدولة العمليات ومتابعة الإجراء", Icons.Default.LocalHospital, Color(0xFFB65C5C),
        listOf("اسم المريض" to "اسم المريض", "نوع العملية" to "اسم العملية", "الجراح" to "اسم الجراح", "التاريخ والوقت" to "موعد العملية", "الحالة" to "مجدولة / جارية / مكتملة", "ملاحظات" to "ملاحظات")),
    Module("inventory", "المخزون الطبي", "الأصناف والكميات وحد إعادة الطلب", Icons.Default.Storage, Color(0xFF478A69),
        listOf("اسم الصنف" to "اسم الصنف", "رمز الصنف" to "SKU", "الكمية الحالية" to "الكمية", "حد إعادة الطلب" to "الحد الأدنى", "وحدة القياس" to "علبة / قطعة / لتر", "ملاحظات" to "ملاحظات")),
    Module("suppliers", "الموردون والمشتريات", "بيانات الموردين وأوامر الشراء", Icons.Default.Groups, Color(0xFF4E79A7),
        listOf("اسم المورد" to "اسم المورد", "رقم التواصل" to "رقم الهاتف", "الصنف المطلوب" to "الصنف", "الكمية" to "الكمية", "قيمة الشراء" to "المبلغ", "حالة الطلب" to "مسودة / مرسل / مستلم")),
    Module("expenses", "المصروفات والصندوق", "تسجيل المصروفات والحركات النقدية", Icons.Default.Payments, Color(0xFFBC7C32),
        listOf("نوع المصروف" to "نوع المصروف", "المبلغ" to "المبلغ", "العملة" to "YER", "المستفيد" to "اسم المستفيد", "التاريخ" to "YYYY-MM-DD", "المرجع" to "رقم السند")),
    Module("insurance", "التأمين الطبي", "شركات التأمين والمطالبات", Icons.Default.ReceiptLong, Color(0xFF5A78B8),
        listOf("اسم المريض" to "اسم المريض", "شركة التأمين" to "اسم الشركة", "رقم الوثيقة" to "رقم الوثيقة", "رقم المطالبة" to "رقم المطالبة", "المبلغ المطالب به" to "المبلغ", "الحالة" to "قيد المراجعة / مقبولة / مرفوضة")),
    Module("staff", "الموظفون والدوام", "سجلات الموظفين والمناوبات", Icons.Default.Groups, Color(0xFF8173B8),
        listOf("اسم الموظف" to "الاسم الكامل", "الوظيفة" to "الوظيفة", "القسم" to "القسم", "المناوبة" to "صباحي / مسائي / ليلي", "رقم التواصل" to "رقم الهاتف", "الحالة" to "نشط / إجازة")),
    Module("departments", "الأقسام والتخصصات", "إدارة الأقسام والغرف والتخصصات", Icons.Default.Hotel, Color(0xFF568C98),
        listOf("اسم القسم" to "اسم القسم", "الموقع" to "المبنى / الطابق", "المسؤول" to "اسم المسؤول", "عدد الأسرة" to "العدد", "الحالة" to "نشط / غير نشط")),
    Module("documents", "المرفقات والوثائق", "فهرسة الوثائق الإدارية والطبية", Icons.Default.Description, Color(0xFF9B6B8B),
        listOf("عنوان الوثيقة" to "عنوان الوثيقة", "نوع الوثيقة" to "طبية / إدارية / مالية", "رقم الملف المرتبط" to "رقم الملف", "وصف" to "وصف الوثيقة")),
    Module("audit", "سجل العمليات", "تسجيل الملاحظات والعمليات الإدارية", Icons.Default.Analytics, Color(0xFF6F7B8B),
        listOf("اسم المستخدم" to "من قام بالعملية", "نوع العملية" to "إضافة / تعديل / حذف / اعتماد", "القسم" to "القسم", "مرجع السجل" to "رقم السجل", "ملاحظات" to "تفاصيل العملية")),
    Module("emergency", "الطوارئ", "استقبال الحالات العاجلة وفرز الأولوية", Icons.Default.LocalHospital, Color(0xFFC97979),
        listOf("رقم الحالة" to "ER-1001", "اسم المريض" to "اسم المريض", "وقت الوصول" to "YYYY-MM-DD HH:mm", "درجة الفرز" to "حرج / عاجل / مستقر", "الشكوى الرئيسية" to "سبب الحضور", "حالة الحالة" to "قيد التقييم / تحويل / خروج")),
    Module("icu", "العناية المركزة", "متابعة حالات العناية المركزة والأسرة", Icons.Default.MedicalInformation, Color(0xFF7897B7),
        listOf("اسم المريض" to "اسم المريض", "رقم السرير" to "رقم السرير", "الطبيب المسؤول" to "اسم الطبيب", "مستوى الرعاية" to "مراقبة / دعم مكثف", "تاريخ الدخول" to "YYYY-MM-DD", "ملاحظات الفريق" to "ملاحظات")),
    Module("bloodbank", "بنك الدم", "طلبات وحدات الدم ومتابعة التوافق", Icons.Default.Science, Color(0xFFC97979),
        listOf("رقم الطلب" to "BB-1001", "اسم المريض" to "اسم المريض", "فصيلة الدم" to "A+ / O- وغيرها", "مكون الدم" to "كريات / بلازما / صفائح", "عدد الوحدات" to "العدد", "حالة الطلب" to "طلب / توافق / صرف")),
    Module("vaccination", "تحصين وتطعيم الأطفال", "سجل جرعات الطفل ومواعيد الجرعات القادمة", Icons.Default.Medication, Color(0xFF548F88),
        listOf("اسم الطفل" to "الاسم الرباعي", "رقم ملف الطفل" to "رقم الملف الصحي", "تاريخ الميلاد" to "YYYY-MM-DD", "اسم اللقاح" to "اسم اللقاح", "رقم الجرعة" to "جرعة الولادة / الأولى / الثانية / معززة", "تاريخ التطعيم" to "YYYY-MM-DD", "موعد الجرعة التالية" to "YYYY-MM-DD أو اتركه فارغاً", "رقم التشغيلة" to "رقم دفعة اللقاح", "مكان التطعيم" to "الذراع / الفخذ / غير ذلك", "الممارس الصحي" to "اسم الممرض أو الطبيب", "حالة الجرعة" to "تم التطعيم / موعد قادم / تأجيل / تعذر", "ملاحظات" to "ملاحظات وتفاعلات بعد التطعيم")),
    Module("referrals", "الإحالات الطبية", "إحالات المرضى بين التخصصات والمنشآت", Icons.Default.Groups, Color(0xFF7897B7),
        listOf("اسم المريض" to "اسم المريض", "الجهة المحال إليها" to "القسم أو المنشأة", "التخصص المطلوب" to "التخصص", "سبب الإحالة" to "سبب الإحالة", "الطبيب المحيل" to "اسم الطبيب", "حالة الإحالة" to "جديدة / مقبولة / مكتملة")),
    Module("discharge", "الخروج والتلخيص الطبي", "تسجيل الخروج وخطة المتابعة", Icons.Default.Description, Color(0xFF9A91B8),
        listOf("اسم المريض" to "اسم المريض", "رقم التنويم" to "رقم الملف أو الدخول", "تاريخ الخروج" to "YYYY-MM-DD", "التشخيص عند الخروج" to "التشخيص", "خطة المتابعة" to "موعد وتعليمات المتابعة", "حالة الخروج" to "مصرح / تحويل / مغادرة")),
    Module("triage", "فرز المرضى", "تحديد أولوية التقييم في الاستقبال", Icons.Default.Analytics, Color(0xFFD5AD6B),
        listOf("اسم المريض" to "اسم المريض", "وقت الفرز" to "YYYY-MM-DD HH:mm", "مستوى الأولوية" to "أحمر / برتقالي / أصفر / أخضر", "الأعراض الأولية" to "الأعراض", "الممرض المسؤول" to "الاسم", "الحالة" to "بانتظار / تم التقييم")),
    Module("queue", "طابور الانتظار", "متابعة أدوار المرضى في العيادات", Icons.Default.CalendarMonth, Color(0xFF6F9BAE),
        listOf("رقم الدور" to "Q-1001", "اسم المريض" to "اسم المريض", "العيادة" to "اسم العيادة", "الطبيب" to "اسم الطبيب", "وقت الوصول" to "HH:mm", "الحالة" to "منتظر / جارٍ / مكتمل")),
    Module("rooms", "الغرف والأجنحة", "إدارة الغرف والطوابق والطاقة الاستيعابية", Icons.Default.Hotel, Color(0xFF5986A4),
        listOf("رمز الغرفة" to "RM-101", "اسم الجناح" to "اسم الجناح", "الطابق" to "رقم الطابق", "نوع الغرفة" to "فردية / مشتركة / عزل", "عدد الأسرة" to "العدد", "الحالة" to "متاحة / مشغولة / صيانة")),
    Module("sterilization", "التعقيم ومكافحة العدوى", "متابعة دورات التعقيم وملاحظات العدوى", Icons.Default.Science, Color(0xFF6D9D86),
        listOf("رقم الدورة" to "ST-1001", "الجهاز" to "اسم الجهاز", "نوع التعقيم" to "بخار / حراري / كيميائي", "وقت الدورة" to "YYYY-MM-DD HH:mm", "النتيجة" to "ناجحة / تحتاج إعادة", "المسؤول" to "اسم المسؤول")),
    Module("equipment", "الأجهزة الطبية", "جرد الأجهزة وحالتها التشغيلية", Icons.Default.Storage, Color(0xFF7897B7),
        listOf("رمز الجهاز" to "EQ-1001", "اسم الجهاز" to "اسم الجهاز", "القسم" to "القسم", "الرقم التسلسلي" to "الرقم التسلسلي", "آخر صيانة" to "YYYY-MM-DD", "الحالة" to "يعمل / صيانة / خارج الخدمة")),
    Module("maintenance", "الصيانة والبلاغات", "طلبات صيانة الأجهزة والمرافق", Icons.Default.Settings, Color(0xFFD5AD6B),
        listOf("رقم البلاغ" to "MT-1001", "مقدم البلاغ" to "الاسم", "الموقع" to "القسم أو الغرفة", "نوع العطل" to "وصف مختصر", "الأولوية" to "عادي / عاجل", "الحالة" to "جديد / قيد العمل / مغلق")),
    Module("ambulance", "الإسعاف والنقل", "تنظيم طلبات النقل والإسعاف", Icons.Default.LocalHospital, Color(0xFFC97979),
        listOf("رقم الطلب" to "AM-1001", "اسم المريض" to "اسم المريض", "موقع الانطلاق" to "الموقع", "الوجهة" to "الوجهة", "وقت الطلب" to "YYYY-MM-DD HH:mm", "الحالة" to "جديد / في الطريق / مكتمل")),
    Module("diet", "التغذية والوجبات", "تنظيم الوجبات والحمية للمرضى المنومين", Icons.Default.Description, Color(0xFF6D9D86),
        listOf("اسم المريض" to "اسم المريض", "رقم الغرفة والسرير" to "الموقع", "نوع الحمية" to "عادية / سكري / قليلة الملح / أخرى", "نوع الوجبة" to "فطور / غداء / عشاء", "تاريخ التقديم" to "YYYY-MM-DD", "ملاحظات الحساسية" to "ملاحظات")),
    Module("complaints", "الشكاوى والملاحظات", "متابعة شكاوى المرضى ومقترحاتهم", Icons.Default.Description, Color(0xFF9A91B8),
        listOf("رقم الشكوى" to "CP-1001", "مقدم الشكوى" to "الاسم أو مجهول", "القسم المعني" to "القسم", "التفاصيل" to "تفاصيل الشكوى", "الإجراء المتخذ" to "الإجراء", "الحالة" to "جديدة / قيد المعالجة / مغلقة")),
    Module("quality", "الجودة وسلامة المرضى", "متابعة مؤشرات الجودة والحوادث", Icons.Default.Analytics, Color(0xFF548F88),
        listOf("رقم البلاغ" to "QA-1001", "نوع الحدث" to "ملاحظة / حادث / شبه حادث", "القسم" to "القسم", "تاريخ الحدث" to "YYYY-MM-DD", "الإجراء التصحيحي" to "الإجراء", "الحالة" to "مفتوح / تحت المراجعة / مغلق")),
    Module("payroll", "الرواتب والمستحقات", "سجل إداري لمستحقات الموظفين", Icons.Default.ReceiptLong, Color(0xFF7897B7),
        listOf("اسم الموظف" to "اسم الموظف", "الشهر" to "YYYY-MM", "الراتب الأساسي" to "المبلغ", "البدلات" to "المبلغ", "الخصومات" to "المبلغ", "حالة الصرف" to "قيد الإعداد / مصروف")),
    Module("attendance", "الحضور والانصراف", "تسجيل الحضور والمناوبات والإجازات", Icons.Default.CalendarMonth, Color(0xFF9A91B8),
        listOf("اسم الموظف" to "اسم الموظف", "التاريخ" to "YYYY-MM-DD", "وقت الحضور" to "HH:mm", "وقت الانصراف" to "HH:mm", "نوع السجل" to "حضور / غياب / إجازة", "ملاحظات" to "ملاحظات")),
    Module("contracts", "العقود والاتفاقيات", "فهرسة العقود مع الموردين والجهات", Icons.Default.Description, Color(0xFF6F7B8B),
        listOf("رقم العقد" to "CT-1001", "اسم الطرف" to "اسم الجهة", "نوع العقد" to "توريد / صيانة / خدمة", "تاريخ البداية" to "YYYY-MM-DD", "تاريخ الانتهاء" to "YYYY-MM-DD", "الحالة" to "مسودة / ساري / منتهي")),
    Module("certificates", "الشهادات والتقارير الطبية", "فهرسة الشهادات الصادرة للمرضى", Icons.Default.Description, Color(0xFF6F9BAE),
        listOf("رقم الشهادة" to "MC-1001", "اسم المريض" to "اسم المريض", "نوع الشهادة" to "إجازة مرضية / تقرير / إفادة", "تاريخ الإصدار" to "YYYY-MM-DD", "الطبيب المصدر" to "اسم الطبيب", "ملاحظات" to "ملاحظات")),
    Module("consents", "الموافقات الطبية", "تسجيل مراجع الموافقات والإقرارات", Icons.Default.Description, Color(0xFF9B6B8B),
        listOf("اسم المريض" to "اسم المريض", "نوع الموافقة" to "عملية / تخدير / إجراء", "الطبيب المسؤول" to "اسم الطبيب", "تاريخ الموافقة" to "YYYY-MM-DD", "حالة التوثيق" to "مسودة / موثقة", "مرجع الوثيقة" to "رقم المرجع")),
    Module("handover", "تسليم المناوبات", "توثيق نقاط التسليم بين الفرق", Icons.Default.Groups, Color(0xFF5986A4),
        listOf("القسم" to "القسم", "المناوبة المنتهية" to "صباحية / مسائية / ليلية", "المناوبة المستلمة" to "صباحية / مسائية / ليلية", "المسؤول المسلم" to "الاسم", "المسؤول المستلم" to "الاسم", "نقاط التسليم" to "ملخص التسليم")),
    Module("visitors", "الزوار والمرافقون", "سجل إداري لزيارة المرضى", Icons.Default.Groups, Color(0xFF8173B8),
        listOf("اسم الزائر" to "اسم الزائر", "اسم المريض" to "اسم المريض", "صلة القرابة" to "صلة القرابة", "وقت الدخول" to "YYYY-MM-DD HH:mm", "وقت الخروج" to "YYYY-MM-DD HH:mm", "ملاحظات" to "ملاحظات")),
    Module("cashier", "نقطة التحصيل", "سجل حركات التحصيل اليومية", Icons.Default.Payments, Color(0xFFD0A06E),
        listOf("رقم السند" to "RC-1001", "اسم المريض أو الجهة" to "الاسم", "نوع الحركة" to "قبض / استرداد", "المبلغ" to "المبلغ", "طريقة الدفع" to "نقد / تحويل", "الموظف" to "اسم المحصل")),
    Module("insurance_companies", "شركات التأمين", "إدارة جهات التأمين وبيانات التواصل", Icons.Default.ReceiptLong, Color(0xFF5A78B8),
        listOf("اسم الشركة" to "اسم الشركة", "رقم التواصل" to "رقم الهاتف", "البريد الإلكتروني" to "البريد", "مسؤول التواصل" to "الاسم", "رقم العقد" to "رقم العقد", "الحالة" to "نشطة / غير نشطة")),
    Module("lab_catalog", "دليل الفحوصات", "تعريف الفحوصات وأسعارها المرجعية", Icons.Default.Science, Color(0xFFD0A06E),
        listOf("رمز الفحص" to "LAB-1001", "اسم الفحص" to "اسم الفحص", "القسم" to "كيمياء / دم / أحياء دقيقة", "السعر المرجعي" to "المبلغ", "مدة النتيجة" to "المدة المتوقعة", "الحالة" to "نشط / غير نشط")),
    Module("pharmacy_suppliers", "موردو الأدوية", "فهرسة موردي الأدوية ومواعيد التوريد", Icons.Default.Medication, Color(0xFF6D9D86),
        listOf("اسم المورد" to "اسم المورد", "اسم الدواء أو الصنف" to "الصنف", "رقم التشغيلة" to "رقم التشغيلة", "تاريخ الصلاحية" to "YYYY-MM-DD", "الكمية" to "الكمية", "حالة التوريد" to "متوقع / مستلم / متأخر")),
    Module("shifts", "جدولة المناوبات", "تخطيط مناوبات الأطباء والتمريض", Icons.Default.CalendarMonth, Color(0xFF9A91B8),
        listOf("اسم الموظف" to "اسم الموظف", "القسم" to "القسم", "تاريخ المناوبة" to "YYYY-MM-DD", "وقت البداية" to "HH:mm", "وقت النهاية" to "HH:mm", "نوع المناوبة" to "صباحية / مسائية / ليلية")),
    Module("refills", "إعادة صرف الأدوية", "متابعة طلبات إعادة الصرف", Icons.Default.Medication, Color(0xFF548F88),
        listOf("اسم المريض" to "اسم المريض", "اسم الدواء" to "اسم الدواء", "رقم الوصفة" to "RX-1001", "تاريخ الطلب" to "YYYY-MM-DD", "الكمية المطلوبة" to "الكمية", "حالة الطلب" to "جديد / موافق عليه / مصروف")),
    Module("incidents", "الحوادث والإصابات", "توثيق الحوادث الإدارية داخل المنشأة", Icons.Default.Analytics, Color(0xFFC97979),
        listOf("رقم الحادث" to "IN-1001", "تاريخ الحادث" to "YYYY-MM-DD HH:mm", "الموقع" to "القسم أو الغرفة", "نوع الحادث" to "الوصف", "الإجراء الفوري" to "الإجراء", "حالة المتابعة" to "مفتوح / مغلق")),
    Module("dialysis", "غسيل الكلى", "جدولة جلسات الغسيل ومتابعة الحالة", Icons.Default.MedicalInformation, Color(0xFF5986A4),
        listOf("اسم المريض" to "اسم المريض", "رقم الجلسة" to "HD-1001", "تاريخ الجلسة" to "YYYY-MM-DD", "مدة الجلسة" to "بالدقائق", "الطبيب المسؤول" to "اسم الطبيب", "ملاحظات الجلسة" to "ملاحظات")),
    Module("anesthesia", "التخدير والإنعاش", "تقييم ما قبل التخدير ومتابعة الإفاقة", Icons.Default.LocalHospital, Color(0xFFC97979),
        listOf("اسم المريض" to "اسم المريض", "رقم العملية" to "رقم العملية", "نوع التخدير" to "عام / موضعي / نصفي", "طبيب التخدير" to "اسم الطبيب", "تقييم ما قبل التخدير" to "التقييم", "حالة الإفاقة" to "مستقر / يحتاج متابعة")),
    Module("dental", "عيادة الأسنان", "مواعيد الأسنان وخطة العلاج", Icons.Default.MedicalServices, Color(0xFF548F88),
        listOf("اسم المريض" to "اسم المريض", "رقم الملف" to "رقم الملف", "رقم السن" to "رقم السن", "الإجراء" to "فحص / حشو / خلع / تنظيف", "الطبيب" to "اسم الطبيب", "موعد المتابعة" to "YYYY-MM-DD")),
    Module("pediatrics", "طب الأطفال وحديثو الولادة", "متابعة الأطفال والمواليد", Icons.Default.MedicalInformation, Color(0xFF7897B7),
        listOf("اسم الطفل" to "اسم الطفل", "رقم ملف الطفل" to "رقم الملف", "تاريخ الميلاد" to "YYYY-MM-DD", "الوزن" to "كجم", "اسم ولي الأمر" to "اسم ولي الأمر", "ملاحظات المتابعة" to "ملاحظات")),
    Module("homecare", "الرعاية المنزلية", "تنظيم الزيارات وخطة الرعاية المنزلية", Icons.Default.Home, Color(0xFF6D9D86),
        listOf("اسم المريض" to "اسم المريض", "عنوان الزيارة" to "المنطقة / العنوان", "مقدم الرعاية" to "اسم الموظف", "تاريخ الزيارة" to "YYYY-MM-DD", "نوع الخدمة" to "تمريض / علاج / متابعة", "ملاحظات" to "ملاحظات")),
    Module("leave_requests", "الإجازات وطلبات الموظفين", "طلبات الإجازة والموافقة الإدارية", Icons.Default.CalendarMonth, Color(0xFF9A91B8),
        listOf("اسم الموظف" to "اسم الموظف", "نوع الإجازة" to "سنوية / مرضية / طارئة", "تاريخ البداية" to "YYYY-MM-DD", "تاريخ النهاية" to "YYYY-MM-DD", "سبب الطلب" to "السبب", "حالة الطلب" to "جديد / موافق / مرفوض")),
)

@Composable
private fun HospitalEntry(repository: HospitalRepository) {
    val context = LocalContext.current
    var entered by remember {
        mutableStateOf(FirebaseAuthGateway.currentUser(context) != null)
    }
    if (entered) {
        HospitalApp(repository)
    } else {
        AuthScreen(
            onAuthenticated = { entered = true },
            onContinueAsGuest = {
                if (BuildConfig.DEBUG) entered = true
            }
        )
    }
}

@Composable
fun HospitalApp(repository: HospitalRepository) {
    val context = LocalContext.current
    var authenticated by remember { mutableStateOf(FirebaseAuthGateway.currentUser(context) != null) }
    if (!authenticated) {
        AuthScreen(
            onAuthenticated = { authenticated = true },
            onContinueAsGuest = {
                if (BuildConfig.DEBUG) authenticated = true
            }
        )
    } else {
        HospitalWorkspace(repository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HospitalWorkspace(repository: HospitalRepository) {
    var selected by remember { mutableStateOf("home") }
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(selected) {
        if (selected == "doctor_workspace") {
            selected = "home"
            context.startActivity(Intent(context, DoctorWorkflowActivity::class.java))
        }
    }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val businessProfile by repository.businessProfile().collectAsState(initial = null)
    LaunchedEffect(Unit) { repository.seedDefaultReferenceCatalogs() }
    val all by repository.allRecords().collectAsState(initial = emptyList())
    val emergencyCases by repository.emergencyCases().collectAsState(initial = emptyList())
    val icuStays by repository.icuStays().collectAsState(initial = emptyList())
    val inventoryItems by repository.inventoryItems().collectAsState(initial = emptyList())
    val labOrders by repository.labOrders().collectAsState(initial = emptyList())
    val radiologyOrders by repository.radiologyOrders().collectAsState(initial = emptyList())
    val maintenanceTickets by repository.maintenanceTickets().collectAsState(initial = emptyList())
    var localWorkflowRows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(selected, all) {
        delay(150)
        localWorkflowRows = withContext(Dispatchers.IO) { repository.localWorkflowRows(selected) }
    }
    val databaseExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            exportStatus = "تم إلغاء تصدير قاعدة البيانات."
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val snapshot = HospitalDatabaseExport.toJson(HospitalDatabase.get(context))
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("تعذر فتح الملف للكتابة")
                        output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(snapshot) }
                    }
                }
                exportStatus = if (result.isSuccess) {
                    "تم تصدير نسخة JSON من جداول قاعدة البيانات المحلية."
                } else {
                    "تعذر تصدير قاعدة البيانات. تحقق من مساحة التخزين وحاول مجدداً."
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) {
            exportStatus = "تم إلغاء التصدير."
        } else {
            val csvSnapshot = HospitalExport.toCsv(all)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("تعذر فتح الملف للكتابة")
                        output.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write("\uFEFF")
                            writer.write(csvSnapshot)
                        }
                    }
                }
                exportStatus = if (result.isSuccess) {
                    "تم تصدير ${all.size} سجل إلى ملف CSV."
                } else {
                    "تعذر التصدير. تحقق من مساحة التخزين وحاول مجدداً."
                }
            }
        }
    }
    BackHandler(enabled = showMenu || selected != "home") {
        if (showMenu) showMenu = false else selected = "home"
    }
    val currentModule = modules.find { it.key == selected }
    val specializedRows = when (selected) {
        "emergency" -> emergencyCases.map { it.caseNumber to "${it.patientName} • ${it.triageLevel} • ${it.status}" }
        "icu" -> icuStays.map { "${it.patientName} • ${it.bedNumber}" to "${it.attendingDoctor} • ${it.careLevel} • ${it.admissionDate}" }
        "inventory" -> inventoryItems.map { "${it.name} (${it.sku})" to "الكمية: ${it.quantity} • حد الطلب: ${it.reorderLevel} ${it.unit}" }
        "radiology" -> radiologyOrders.map { "${it.patientName} • ${it.imagingType}" to "${it.requestingDoctor} • ${it.status} • ${it.report}" }
        "maintenance" -> maintenanceTickets.map { it.ticketNumber to "${it.location} • ${it.issue} • ${it.priority} • ${it.status}" }
        "pharmacy", "beds", "admissions", "appointments", "users", "encounters", "invoices", "payments" -> localWorkflowRows
        "lab" -> localWorkflowRows.ifEmpty { labOrders.map { (it.patientName + " • " + it.testName) to (it.status + " • " + it.result) } }
        else -> emptyList()
    }
    val screenTitle = currentModule?.title ?: when (selected) {
        "home" -> "لوحة التحكم"
        "reports" -> "التقارير والمؤشرات"
        "settings" -> "الإعدادات"
        "business" -> "بيانات المنشأة"
        "catalogs" -> "القوائم المرجعية"
        else -> "نظام المستشفى"
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, secondary = Navy,
            background = Canvas, onBackground = Ink, surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFEDF2F4), outline = Border,
            secondaryContainer = Mint, onSecondaryContainer = Navy
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(screenTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Navy)
                            Text("نظام إدارة المستشفى", fontSize = 11.sp, color = Muted)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.Menu, "القائمة", tint = Navy)
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = "settings" }) {
                            Icon(Icons.Default.Settings, "الإعدادات", tint = Navy)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Canvas)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    val tabs = listOf(
                        Triple("home", "الرئيسية", Icons.Default.Home),
                        Triple("patients", "المرضى", Icons.Default.Groups),
                        Triple("appointments", "المواعيد", Icons.Default.CalendarMonth),
                        Triple("invoices", "المالية", Icons.Default.ReceiptLong),
                        Triple("settings", "الإعدادات", Icons.Default.Settings)
                    )
                    tabs.forEach { (key, label, icon) ->
                        NavigationBarItem(
                            selected = selected == key,
                            onClick = { selected = key; showMenu = false },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal, selectedTextColor = Teal,
                                indicatorColor = Mint
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    showMenu || selected == "menu" -> ModuleMenu(onSelect = { selected = it; showMenu = false })
                    selected == "home" -> Dashboard(
                        all = all,
                        onSelect = { selected = it },
                        onAdd = { key -> selected = key }
                    )
                    selected == "reports" -> Reports(all)
                    selected == "audit" -> AuditHistoryScreen(repository)
                    selected == "doctor_workspace" -> Box(Modifier.fillMaxSize())
                    selected == "catalogs" -> ReferenceCatalogScreen(repository)
                    selected == "business" -> BusinessProfileScreen(
                        profile = businessProfile,
                        onSave = { profile -> repository.saveBusinessProfile(profile) }
                    )
                    selected == "vaccination" -> VaccinationScreen(
                        repository = repository,
                        onDelete = { id -> scope.launch { repository.deleteRecord(id) } }
                    )
                    selected == "emergency" -> EmergencyScreen(
                        repository = repository,
                        onEdit = { id, title, details -> repository.updateRecord(id, title, details) },
                        onDelete = { id -> scope.launch { repository.deleteRecord(id) } }
                    )
                    selected == "settings" -> SettingsScreen(
                        allCount = all.size,
                        exportStatus = exportStatus,
                        onBusinessProfile = { selected = "business" },
                        onReferenceCatalogs = { selected = "catalogs" },
                        onExport = {
                            exportStatus = null
                            val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            exportLauncher.launch("hospital-records-$timestamp.csv")
                        },
                        onExportDatabase = {
                            exportStatus = null
                            val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            databaseExportLauncher.launch("hospital-database-$timestamp.json")
                        }
                    )
                    currentModule != null -> ModuleScreen(
                        repository = repository,
                        module = currentModule,
                        records = all.filter { it.category == currentModule.key },
                        specializedRows = specializedRows,
                        onSave = { title, details ->
                            repository.saveRecord(HospitalRecord(category = currentModule.key, title = title, details = details))
                        },
                        onEdit = { id, title, details -> repository.updateRecord(id, title, details) },
                        onDelete = { id -> scope.launch { repository.deleteRecord(id) } }
                    )
                    else -> Dashboard(all, { selected = it }, { selected = it })
                }
            }
        }
    }
    }
}

@Composable
private fun Dashboard(all: List<HospitalRecord>, onSelect: (String) -> Unit, onAdd: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val groups = listOf(
        Triple("الرعاية والملفات الطبية", "المرضى والزيارات والمواعيد", listOf("patients", "doctors", "doctor_workspace", "appointments", "encounters", "nursing", "admissions", "beds", "discharge", "referrals", "consents", "handover")),
        Triple("الطوارئ والرعاية المتخصصة", "خدمات الحالات الحرجة والعيادات", listOf("emergency", "triage", "queue", "icu", "ambulance", "bloodbank", "operations", "anesthesia", "dialysis", "dental", "pediatrics", "homecare", "vaccination")),
        Triple("الفحوصات والصيدلية", "المختبر والأشعة والأدوية والمخزون", listOf("lab", "lab_catalog", "radiology", "pharmacy", "prescriptions", "refills", "inventory", "pharmacy_suppliers", "suppliers", "sterilization", "equipment")),
        Triple("المالية والتحصيل", "الفواتير والمدفوعات والتأمين", listOf("invoices", "payments", "cashier", "expenses", "insurance", "insurance_companies", "payroll", "contracts")),
        Triple("الإدارة والموارد", "الموظفون والأقسام والجودة والخدمات", listOf("users", "staff", "departments", "attendance", "shifts", "leave_requests", "visitors", "diet", "complaints", "quality", "incidents", "maintenance", "rooms", "documents", "certificates", "audit"))
    )
    val filteredGroups = remember(query) {
        groups.map { group ->
            group.first to group.third.mapNotNull { key -> modules.find { it.key == key } }
                .filter { it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true) }
        }.filter { it.second.isNotEmpty() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xFF415B70), Color(0xFF548F88))),
                    RoundedCornerShape(24.dp)
                ).padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(Color.White.copy(alpha = .15f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LocalHospital, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("لوحة التحكم", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                            Text("نظام إدارة المستشفى", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("اختر القسم المطلوب مباشرة، أو ابحث عن الخدمة.", color = Color.White.copy(alpha = .92f), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = Color.White.copy(alpha = .15f), shape = RoundedCornerShape(10.dp)) {
                            Text("محلي على الجهاز", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                        }
                        Surface(color = Color.White.copy(alpha = .15f), shape = RoundedCornerShape(10.dp)) {
                            Text("${all.size} سجل عام", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ابحث عن قسم أو خدمة") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }
        item {
            Text("الوصول السريع", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(
                    Triple("patients", "المرضى", Icons.Default.Groups),
                    Triple("appointments", "المواعيد", Icons.Default.CalendarMonth),
                    Triple("emergency", "الطوارئ", Icons.Default.Emergency),
                    Triple("cashier", "التحصيل", Icons.Default.Payments)
                ).forEach { (key, label, icon) ->
                    Card(
                        modifier = Modifier.weight(1f).clickable { onAdd(key) },
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(icon, contentDescription = label, tint = Teal, modifier = Modifier.size(23.dp))
                            Text(label, color = Navy, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (filteredGroups.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Text("لا توجد أقسام تطابق البحث.", modifier = Modifier.padding(20.dp), color = Muted)
                }
            }
        } else {
            filteredGroups.forEach { (groupTitle, groupModules) ->
                item(key = "group-$groupTitle") {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(groupTitle, color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(when (groupTitle) {
                            "الرعاية والملفات الطبية" -> "ملفات المرضى والزيارات اليومية"
                            "الطوارئ والرعاية المتخصصة" -> "الخدمات الحرجة والرعاية التخصصية"
                            "الفحوصات والصيدلية" -> "الفحوصات والتصوير والأدوية والمستلزمات"
                            "المالية والتحصيل" -> "الفواتير والمدفوعات والإدارة المالية"
                            else -> "إدارة العاملين والتشغيل والخدمات المساندة"
                        }, color = Muted, fontSize = 11.sp)
                    }
                }
                items(groupModules.chunked(2), key = { row -> "row-$groupTitle-" + row.joinToString("-") { it.key } }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { module ->
                            Card(
                                modifier = Modifier.weight(1f).clickable { onSelect(module.key) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(38.dp).background(module.color.copy(alpha = .12f), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(module.icon, contentDescription = null, tint = module.color, modifier = Modifier.size(21.dp))
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(module.title, color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2)
                                        Text("فتح القسم", color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8EC)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0E5D2))
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFFB77816))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "هذه نسخة تجريبية بقاعدة بيانات محلية. بعض الأقسام تحفظ سجلات عامة وليست كلها مرتبطة بجداول طبية متخصصة، ولا توجد مزامنة آمنة مع الخادم حتى الآن.",
                        color = Color(0xFF805C20), fontSize = 11.sp, lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleMenu(onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filteredModules = remember(query) {
        modules.filter { module ->
            module.title.contains(query.trim(), ignoreCase = true) ||
                module.subtitle.contains(query.trim(), ignoreCase = true)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("جميع أقسام النظام", fontSize = 22.sp, color = Navy, fontWeight = FontWeight.Bold)
            Text("اختر القسم الذي تريد فتحه", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ابحث عن قسم أو خدمة") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث") },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }
        if (filteredModules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.SearchOff, contentDescription = null, tint = Muted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("لا توجد أقسام تطابق البحث", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        items(filteredModules) { module ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(module.key) },
                shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(module.color.copy(alpha = .12f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Icon(module.icon, null, tint = module.color)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(module.title, color = Navy, fontWeight = FontWeight.Bold)
                        Text(module.subtitle, color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronLeft, null, tint = Muted)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable { onSelect("reports") }, colors = CardDefaults.cardColors(containerColor = Mint)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Analytics, null, tint = Teal)
                    Spacer(Modifier.width(12.dp))
                    Text("التقارير والمؤشرات", color = Navy, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Required fields are defined per workflow instead of assuming that the first
 * field is the only mandatory value. Modules not listed here retain the
 * conservative legacy rule: their first field is required.
 */
private val requiredFieldLabelsByModule = mapOf(
    "patients" to setOf("رقم الملف", "اسم المريض"),
    "users" to setOf("اسم المستخدم", "الدور"),
    "doctors" to setOf("الرقم الوظيفي", "اسم الطبيب"),
    "appointments" to setOf("اسم المريض", "اسم الطبيب", "التاريخ والوقت", "وقت الانتهاء"),
    "encounters" to setOf("اسم المريض", "الشكوى الرئيسية"),
    "lab" to setOf("اسم المريض", "اسم الفحص", "حالة الفحص"),
    "pharmacy" to setOf("اسم الدواء", "رمز الدواء", "الكمية"),
    "admissions" to setOf("اسم المريض", "القسم / الجناح", "حالة التنويم"),
    "invoices" to setOf("رقم الفاتورة", "اسم المريض", "المبلغ", "العملة"),
    "payments" to setOf("رقم الفاتورة", "المبلغ المدفوع", "طريقة الدفع"),
    "prescriptions" to setOf("اسم المريض", "اسم الدواء", "الجرعة"),
    "beds" to setOf("الجناح", "رقم السرير", "الحالة"),
    "nursing" to setOf("اسم المريض"),
    "radiology" to setOf("اسم المريض", "نوع التصوير", "الحالة"),
    "operations" to setOf("اسم المريض", "نوع العملية", "التاريخ والوقت"),
    "inventory" to setOf("اسم الصنف", "الكمية الحالية", "حد إعادة الطلب", "وحدة القياس"),
    "suppliers" to setOf("اسم المورد", "رقم التواصل"),
    "expenses" to setOf("نوع المصروف", "المبلغ", "التاريخ"),
    "insurance" to setOf("اسم المريض", "شركة التأمين", "رقم المطالبة"),
    "staff" to setOf("اسم الموظف", "الوظيفة", "القسم"),
    "departments" to setOf("اسم القسم"),
    "emergency" to setOf("رقم الحالة", "وقت الوصول", "درجة الفرز", "حالة الحالة"),
    "icu" to setOf("اسم المريض", "رقم السرير", "تاريخ الدخول"),
    "bloodbank" to setOf("رقم الطلب", "اسم المريض", "فصيلة الدم", "عدد الوحدات"),
    "vaccination" to setOf("اسم الطفل", "اسم اللقاح", "تاريخ التطعيم"),
    "referrals" to setOf("اسم المريض", "الجهة المحال إليها", "سبب الإحالة"),
    "discharge" to setOf("اسم المريض", "تاريخ الخروج", "حالة الخروج"),
    "triage" to setOf("اسم المريض", "وقت الفرز", "مستوى الأولوية"),
    "queue" to setOf("رقم الدور", "اسم المريض", "الحالة"),
    "rooms" to setOf("رمز الغرفة", "الطابق", "الحالة"),
    "sterilization" to setOf("رقم الدورة", "الجهاز", "وقت الدورة", "النتيجة"),
    "equipment" to setOf("رمز الجهاز", "اسم الجهاز", "الحالة"),
    "maintenance" to setOf("رقم البلاغ", "مقدم البلاغ", "نوع العطل", "الحالة"),
    "ambulance" to setOf("رقم الطلب", "موقع الانطلاق", "الوجهة", "الحالة"),
    "documents" to setOf("عنوان الوثيقة", "نوع الوثيقة"),
    "audit" to setOf("اسم المستخدم", "نوع العملية", "القسم")
)

private fun requiredFieldsForModule(module: Module): Set<String> =
    requiredFieldLabelsByModule[module.key]
        ?.intersect(module.fields.map { it.first }.toSet())
        ?.takeIf { it.isNotEmpty() }
        ?: setOfNotNull(module.fields.firstOrNull()?.first)

private val numericFieldLabels = setOf(
    "العمر", "النبض", "التنفس", "الحرارة", "عدد الأسرة", "عدد الوحدات",
    "حد إعادة الطلب", "المبلغ", "المبلغ المدفوع", "الكمية", "الكمية الحالية",
    "الكمية المطلوبة", "قيمة الشراء", "المبلغ المطالب به", "الطابق", "الوزن",
    "رقم السن", "مدة الجلسة", "الراتب الأساسي", "البدلات", "الخصومات",
    "السعر المرجعي", "مدة النتيجة"
)

private val integerFieldLabels = setOf(
    "العمر", "النبض", "التنفس", "عدد الأسرة", "عدد الوحدات", "الطابق", "الكمية", "الكمية المطلوبة",
    "رقم السن", "مدة الجلسة", "مدة النتيجة"
)

private val longTextFieldLabels = setOf(
    "ملاحظات", "ملاحظات إدارية", "ملاحظات التمريض", "ملاحظات الفريق",
    "التشخيص والملاحظات", "النتيجة", "التقرير", "الجرعة / الوصفة",
    "خطة المتابعة", "الشكوى الرئيسية", "الأعراض الأولية", "ملاحظات الحساسية",
    "وصف الوثيقة", "سبب الزيارة", "السبب", "الوصف", "المشكلة", "تفاصيل العملية",
    "التشخيص عند الخروج", "سبب الإحالة", "تعليمات", "النتيجة أو ملاحظات"
)

private fun optionsForField(label: String, hint: String): List<String> {
    val tests = listOf("صورة دم كاملة (CBC)", "سكر صائم", "سكر عشوائي", "HbA1c", "وظائف الكلى", "وظائف الكبد", "تحليل بول كامل", "تحليل براز", "دهون الدم", "فحص الحمل", "TSH", "CRP", "ESR", "فيتامين D", "فيتامين B12", "فصيلة الدم وRh", "PT/INR", "مزرعة بول", "مزرعة دم", "اختبار الملاريا", "اختبار حمى الضنك")
    val drugs = listOf("باراسيتامول", "إيبوبروفين", "أموكسيسيلين", "أزيثروميسين", "أوميبرازول", "ميترونيدازول", "سيتريزين", "لوراتادين", "ميتفورمين", "أملوديبين", "لوسارتان", "أتورفاستاتين", "محلول إماهة فموي (ORS)", "محلول ملحي 0.9%", "حديد", "حمض الفوليك", "فيتامين D", "سالبوتامول", "إنسولين")
    val diseases = listOf("ارتفاع ضغط الدم", "داء السكري النوع الأول", "داء السكري النوع الثاني", "فقر الدم", "الربو", "التهاب الشعب الهوائية", "التهاب رئوي", "التهاب المسالك البولية", "التهاب المعدة والأمعاء", "ارتجاع معدي مريئي", "الصداع النصفي", "حساسية الأنف", "مرض القلب الإقفاري", "قصور القلب", "مرض الكلى المزمن", "اضطراب الغدة الدرقية", "الملاريا", "حمى الضنك", "التهاب الكبد الفيروسي", "السل", "التهاب المفاصل", "عدوى جلدية", "أخرى / غير محدد")
    if (label == "الدور") return listOf("مدير", "طبيب", "ممرض", "استقبال", "مختبر", "صيدلي", "محاسب")
    if (label == "الحالة" && hint.contains("نشط")) return listOf("نشط", "غير نشط")
    if (label == "الحالة" && hint.contains("متاح")) return listOf("متاح", "مشغول", "صيانة")
    if (label == "الحالة" && hint.contains(" / ")) return hint.split(" / ").map { it.trim() }.filter { it.isNotBlank() }
    when (label) {
        "اسم الفحص", "نوع الفحص", "الفحص المطلوب" -> return tests
        "اسم الدواء", "الدواء", "اسم العلاج" -> return drugs
        "اسم المرض", "المرض", "التشخيص المبدئي", "التشخيص" -> return diseases
        "التخصص" -> return listOf("طب عام", "طب الأسرة", "طب الأطفال", "النساء والتوليد", "الجراحة العامة", "العظام", "الباطنة", "القلب", "الأنف والأذن والحنجرة", "العيون", "الجلدية", "المسالك البولية", "الأعصاب", "الطوارئ", "التخدير", "الأشعة", "المختبر", "طب الأسنان", "التمريض", "الصيدلة")
        "القسم", "القسم / الجناح", "الجناح" -> return listOf("الاستقبال", "الطوارئ", "الباطنة", "الجراحة", "الأطفال", "النساء والتوليد", "العناية المركزة", "المختبر", "الأشعة", "الصيدلية", "العيادات الخارجية", "التنويم", "العمليات", "التعقيم", "الإدارة")
        "حالة الفحص", "الحالة" -> return listOf("مطلوب", "قيد التنفيذ", "مكتمل", "ملغي")
        "حالة التنويم" -> return listOf("منوّم", "خرج", "محول")
        "حالة الجرعة" -> return listOf("مجدول", "تم التطعيم", "تأخر", "ملغي")
        "العملة" -> return listOf("YER", "USD", "SAR")
        "طريقة الدفع" -> return listOf("نقداً", "تحويل بنكي", "بطاقة", "تأمين")
        "فصيلة الدم" -> return listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
        "رقم الجرعة" -> return listOf("الأولى", "الثانية", "الثالثة", "معززة")
        "مستوى الأولوية" -> return listOf("أحمر", "برتقالي", "أصفر", "أخضر")
        "درجة الفرز" -> return listOf("حرج", "عاجل", "مستقر")
        "نوع التصوير" -> return listOf("أشعة سينية", "CT", "MRI", "موجات فوق صوتية")
        "وحدة القياس" -> return listOf("قطعة", "علبة", "عبوة", "لتر", "مل", "كغ")
        "المناوبة" -> return listOf("صباحي", "مسائي", "ليلي")
        "الضغط", "عنوان الزيارة", "الموقع" -> return emptyList()
    }
    return if (hint.contains(" / ")) hint.split(" / ").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("مثال:") } else emptyList()
}

private fun isMonthField(label: String, hint: String): Boolean =
    label == "الشهر" && hint == "YYYY-MM"

private fun isDateField(label: String, hint: String): Boolean =
    label.startsWith("تاريخ") || label.contains("التاريخ") ||
        hint.startsWith("YYYY-MM-DD") || isMonthField(label, hint)

private fun isDateTimeField(label: String, hint: String): Boolean =
    (isDateField(label, hint) && hint.contains("HH:mm")) ||
        label == "التاريخ والوقت" || hint == "YYYY-MM-DD HH:mm"

private fun isTimeOnlyField(label: String, hint: String): Boolean =
    hint == "HH:mm" || (label == "وقت الوصول" && hint == "HH:mm")

/** Validate actual calendar/time values, not just the text shape. */
private fun isValidTemporalValue(label: String, hint: String, raw: String): Boolean = when {
    isDateTimeField(label, hint) -> HospitalFieldValidation.isValidDateTime(raw)
    isMonthField(label, hint) -> HospitalFieldValidation.isValidMonth(raw)
    isDateField(label, hint) -> HospitalFieldValidation.isValidDate(raw)
    isTimeOnlyField(label, hint) -> HospitalFieldValidation.isValidTime(raw)
    else -> true
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ModuleInputField(
    label: String,
    hint: String,
    value: String,
    required: Boolean,
    databaseOptions: List<String> = emptyList(),
    onValueChange: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = if (databaseOptions.isNotEmpty()) databaseOptions else remember(label, hint) { optionsForField(label, hint) }
    var expanded by remember(label) { mutableStateOf(false) }
    val dateTimeField = isDateTimeField(label, hint)
    val monthField = isMonthField(label, hint)
    val dateField = isDateField(label, hint) || dateTimeField
    val timeOnly = isTimeOnlyField(label, hint)

    val onPickDate = {
        val calendar = java.util.Calendar.getInstance()
        val existingDate = if (monthField && value.matches(Regex("\\d{4}-\\d{2}"))) "$value-01" else value.take(10)
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(existingDate)
        }.getOrNull()
        if (parsed != null) calendar.time = parsed
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val pickedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                val picked = when {
                    monthField -> pickedDate.take(7)
                    dateTimeField -> {
                        val timePart = value.substringAfter(' ', "09:00").takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "09:00"
                        "$pickedDate $timePart"
                    }
                    else -> pickedDate
                }
                onValueChange(picked)
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    val onPickTime = {
        val currentTime = value.substringAfter(' ', value).takeIf { it.matches(Regex("\\d{2}:\\d{2}")) } ?: "09:00"
        val parts = currentTime.split(":")
        val hour = parts[0].toIntOrNull() ?: 9
        val minute = parts[1].toIntOrNull() ?: 0
        TimePickerDialog(
            context,
            { _, selectedHour, selectedMinute ->
                val pickedTime = String.format(Locale.US, "%02d:%02d", selectedHour, selectedMinute)
                onValueChange(
                    if (dateTimeField) {
                        val date = value.take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        "$date $pickedTime"
                    } else pickedTime
                )
            },
            hour,
            minute,
            true
        ).show()
    }

    val keyboardType = when {
        label.contains("الهاتف") || label.contains("التواصل") -> KeyboardType.Phone
        label.contains("البريد") -> KeyboardType.Email
        label in integerFieldLabels -> KeyboardType.Number
        label in numericFieldLabels -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    val singleLine = label !in longTextFieldLabels && !label.contains("ملاحظات") &&
        label !in setOf("التشخيص عند الخروج", "سبب الإحالة", "الأعراض", "التقرير")

    if (options.isNotEmpty()) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                readOnly = true,
                label = { Text(if (required) "$label *" else label) },
                placeholder = { Text("اختر من القائمة", fontSize = 12.sp) },
                trailingIcon = {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "عرض الخيارات")
                    }
                },
                supportingText = if (required) ({ Text("حقل مطلوب", fontSize = 10.sp, color = Muted) }) else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Teal, unfocusedBorderColor = Border,
                    focusedLabelColor = Teal, cursorColor = Teal
                ),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = dateField || timeOnly,
            label = { Text(if (required) "$label *" else label) },
            placeholder = { Text(hint, fontSize = 12.sp) },
            supportingText = if (required) ({ Text("حقل مطلوب", fontSize = 10.sp, color = Muted) }) else null,
            trailingIcon = when {
                dateTimeField -> ({
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPickDate) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "اختيار التاريخ")
                        }
                        IconButton(onClick = onPickTime) {
                            Icon(Icons.Default.Schedule, contentDescription = "اختيار الوقت")
                        }
                    }
                })
                dateField -> ({
                    IconButton(onClick = onPickDate) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "اختيار التاريخ")
                    }
                })
                timeOnly -> ({
                    IconButton(onClick = onPickTime) {
                        Icon(Icons.Default.Schedule, contentDescription = "اختيار الوقت")
                    }
                })
                else -> null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Teal, unfocusedBorderColor = Border,
                focusedLabelColor = Teal, cursorColor = Teal
            ),
            shape = RoundedCornerShape(14.dp),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
        if (dateTimeField && value.isNotBlank()) {
            Text(
                "يمكن اختيار التاريخ من أيقونة التقويم والوقت من أيقونة الساعة.",
                color = Muted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ModuleScreen(
    repository: HospitalRepository,
    module: Module,
    records: List<HospitalRecord>,
    specializedRows: List<Pair<String, String>>,
    onSave: suspend (String, String) -> Unit,
    onEdit: suspend (Long, String, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var values by remember(module.key) { mutableStateOf(module.fields.associate { it.first to "" }) }
    var savedMessage by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var editingRecord by remember { mutableStateOf<HospitalRecord?>(null) }
    var viewingHistoryRecord by remember { mutableStateOf<HospitalRecord?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDetails by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var searchQuery by remember(module.key) { mutableStateOf("") }
    val requiredFields = remember(module.key) { requiredFieldsForModule(module) }
    val refTests by repository.referenceItems("lab_tests").collectAsState(initial = emptyList())
    val refMeds by repository.referenceItems("medications").collectAsState(initial = emptyList())
    val refDiagnoses by repository.referenceItems("diagnoses").collectAsState(initial = emptyList())
    val refSpecialties by repository.referenceItems("specialties").collectAsState(initial = emptyList())
    val refDepartments by repository.referenceItems("departments").collectAsState(initial = emptyList())
    val refBloodGroups by repository.referenceItems("blood_groups").collectAsState(initial = emptyList())
    val refPaymentMethods by repository.referenceItems("payment_methods").collectAsState(initial = emptyList())
    val refLabStatus by repository.referenceItems("lab_status").collectAsState(initial = emptyList())
    val refAdmissionStatus by repository.referenceItems("admission_status").collectAsState(initial = emptyList())
    val refVaccinationStatus by repository.referenceItems("vaccination_status").collectAsState(initial = emptyList())
    val refImagingTypes by repository.referenceItems("imaging_types").collectAsState(initial = emptyList())
    fun dbOptions(label: String): List<String> = when (label) {
        "اسم الفحص", "نوع الفحص", "الفحص المطلوب" -> refTests
        "اسم الدواء", "الدواء", "اسم العلاج" -> refMeds
        "اسم المرض", "المرض", "التشخيص المبدئي", "التشخيص", "التشخيص عند الخروج" -> refDiagnoses
        "التخصص", "التخصص المطلوب" -> refSpecialties
        "القسم", "القسم / الجناح", "الجناح", "العيادة" -> refDepartments
        "فصيلة الدم" -> refBloodGroups
        "طريقة الدفع" -> refPaymentMethods
        "حالة الفحص" -> refLabStatus
        "حالة التنويم" -> refAdmissionStatus
        "حالة الجرعة" -> refVaccinationStatus
        "نوع التصوير" -> refImagingTypes
        else -> emptyList()
    }.filter { it.active }.map { it.name }
    val filteredRecords = remember(records, searchQuery) {
        records.filter { record ->
            record.title.contains(searchQuery, ignoreCase = true) ||
                record.details.contains(searchQuery, ignoreCase = true)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).background(module.color.copy(alpha = .13f), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                    Icon(module.icon, null, tint = module.color, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(module.title, color = Navy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(module.subtitle, color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (module.key == "lab") "طلب فحص مخبري جديد" else "إضافة سجل جديد", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (module.key == "lab") {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF7ED), RoundedCornerShape(12.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Science, contentDescription = null, tint = Color(0xFFB77935), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("أدخل بيانات المريض والفحص. اترك النتيجة فارغة إذا لم تصدر بعد، ثم حدّث حالة الطلب عند تقدم العمل.", color = Color(0xFF815B2D), fontSize = 12.sp)
                        }
                    }
                    module.fields.forEachIndexed { index, (label, hint) ->
                        ModuleInputField(
                            label = label,
                            hint = hint,
                            value = values[label].orEmpty(),
                            required = label in requiredFields,
                            databaseOptions = dbOptions(label),
                            onValueChange = {
                                values = values + (label to it)
                                savedMessage = false
                                validationMessage = null
                            }
                        )
                    }
                    Button(
                        onClick = {
                            val titleField = module.fields.firstOrNull()?.first
                            val title = titleField?.let { values[it]?.trim() }.orEmpty()
                            val missingRequired = requiredFields.firstOrNull { values[it].isNullOrBlank() }
                            if (missingRequired != null) {
                                validationMessage = "أكمل الحقل المطلوب: $missingRequired."
                                savedMessage = false
                                return@Button
                            }
                            val invalidNumericField = module.fields.firstOrNull { (label, _) ->
                                val raw = values[label]?.trim().orEmpty()
                                if (label !in numericFieldLabels || raw.isBlank()) {
                                    false
                                } else if (label in integerFieldLabels) {
                                    val number = raw.toLongOrNull()
                                    number == null || number < 0
                                } else if (label == "المبلغ" || label == "المبلغ المدفوع") {
                                    !HospitalFieldValidation.isValidMoneyAmount(raw)
                                } else {
                                    val number = raw.toBigDecimalOrNull()
                                    number == null || number.signum() < 0
                                }
                            }
                            if (invalidNumericField != null) {
                                validationMessage = "أدخل رقماً صحيحاً غير سالب في حقل " + invalidNumericField.first + "."
                                savedMessage = false
                                return@Button
                            }
                            val invalidDateField = module.fields.firstOrNull { (label, hint) ->
                                val raw = values[label]?.trim().orEmpty()
                                raw.isNotBlank() && !isValidTemporalValue(label, hint, raw)
                            }
                            if (invalidDateField != null) {
                                validationMessage = "تحقق من تنسيق التاريخ أو الوقت في حقل " + invalidDateField.first + "."
                                savedMessage = false
                                return@Button
                            }
                            val details = module.fields.mapNotNull { (label, _) ->
                                values[label]?.trim()?.takeIf { it.isNotBlank() }?.let { "$label: $it" }
                            }.joinToString("\n")
                            scope.launch {
                                isSaving = true
                                validationMessage = null
                                savedMessage = false
                                try {
                                    onSave(title, details)
                                    values = module.fields.associate { it.first to "" }
                                    savedMessage = true
                                } catch (error: Exception) {
                                    validationMessage = error.message ?: "تعذر حفظ السجل. حاول مرة أخرى."
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("جارٍ الحفظ...")
                        } else {
                            Icon(Icons.Default.Save, null)
                            Spacer(Modifier.width(8.dp))
                            Text("حفظ في قاعدة البيانات")
                        }
                    }
                    validationMessage?.let { Text(it, color = Rose, fontSize = 12.sp) }
                    if (savedMessage) Text("تم حفظ السجل محلياً بنجاح.", color = Teal, fontSize = 12.sp)
                }
            }
        }
        if (specializedRows.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("بيانات القسم التفصيلية", color = Navy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        specializedRows.take(30).forEach { (title, details) ->
                            Column(Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(12.dp)).padding(12.dp)) {
                                Text(title.ifBlank { "سجل بدون عنوان" }, color = Navy, fontWeight = FontWeight.SemiBold)
                                if (details.isNotBlank()) Text(details, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("السجلات المحفوظة", color = Navy, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Surface(color = Mint, shape = CircleShape) {
                    Text(records.size.toString(), color = Teal, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("بحث في سجلات ${module.title}") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }
        if (filteredRecords.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inbox, null, tint = Muted, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("لا توجد سجلات بعد", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(filteredRecords, key = { it.id }) { record ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(38.dp).background(module.color.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(module.icon, null, tint = module.color, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(record.title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val visibleDetails = RecordEditHistory.visibleDetails(record.details)
                            if (visibleDetails.isNotBlank()) Text(visibleDetails, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            Text(SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date(record.createdAt)), color = Muted.copy(alpha = .8f), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { viewingHistoryRecord = record }) {
                                Icon(Icons.Default.History, contentDescription = "عرض سجل التعديلات", tint = SoftBlue)
                            }
                            IconButton(onClick = {
                                editingRecord = record
                                editTitle = record.title
                                editDetails = RecordEditHistory.visibleDetails(record.details)
                                editError = null
                            }) { Icon(Icons.Default.Edit, contentDescription = "تعديل السجل", tint = Teal) }
                            IconButton(onClick = { pendingDeleteId = record.id }) { Icon(Icons.Default.DeleteOutline, contentDescription = "حذف السجل", tint = Rose) }
                        }
                    }
                }
            }
        }
    }
    editingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { if (!isEditing) editingRecord = null },
            title = { Text("تعديل السجل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("سيُحفظ وقت التعديل في سجل التعديلات، مع إبقاء تاريخ الإنشاء الأصلي.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("عنوان السجل") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDetails, onValueChange = { editDetails = it }, label = { Text("تفاصيل السجل") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                    editError?.let { Text(it, color = Rose, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isEditing && editTitle.isNotBlank(), onClick = {
                    val editedFields = RecordEditHistory.visibleDetails(editDetails).lineSequence().mapNotNull { line ->
                        val separator = line.indexOf(": ")
                        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
                    }.toMap()
                    // Validate structured records during edits too; legacy free-text records remain editable.
                    if (editedFields.isNotEmpty()) {
                        val missing = requiredFields.firstOrNull { editedFields[it].isNullOrBlank() }
                        if (missing != null) {
                            editError = "أكمل الحقل المطلوب: $missing."
                            return@TextButton
                        }
                        val invalidDate = module.fields.firstOrNull { (label, hint) ->
                            val raw = editedFields[label].orEmpty()
                            raw.isNotBlank() && !isValidTemporalValue(label, hint, raw)
                        }
                        if (invalidDate != null) {
                            editError = "تحقق من تنسيق التاريخ أو الوقت في حقل " + invalidDate.first + "."
                            return@TextButton
                        }
                        val invalidNumber = module.fields.firstOrNull { (label, _) ->
                            val raw = editedFields[label].orEmpty()
                            if (raw.isBlank() || label !in numericFieldLabels) false
                            else if (label in integerFieldLabels) raw.toLongOrNull()?.let { it < 0 } ?: true
                            else if (label == "المبلغ" || label == "المبلغ المدفوع") !HospitalFieldValidation.isValidMoneyAmount(raw)
                            else raw.toBigDecimalOrNull()?.let { it.signum() < 0 } ?: true
                        }
                        if (invalidNumber != null) {
                            editError = "أدخل رقماً صحيحاً غير سالب في حقل " + invalidNumber.first + "."
                            return@TextButton
                        }
                    }
                    editError = null
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    val savedDetails = RecordEditHistory.append(editDetails, record.details, now)
                    scope.launch {
                        isEditing = true
                        runCatching { onEdit(record.id, editTitle.trim(), savedDetails) }
                            .onSuccess { editingRecord = null }
                            .onFailure { editError = "تعذر حفظ التعديل. لم يتم إغلاق النافذة." }
                        isEditing = false
                    }
                }) { Text(if (isEditing) "جارٍ الحفظ..." else "حفظ التعديل") }
            },
            dismissButton = { TextButton(enabled = !isEditing, onClick = { editingRecord = null }) { Text("إلغاء") } }
        )
    }
    viewingHistoryRecord?.let { record ->
        val entries = RecordEditHistory.entries(record.details)
        AlertDialog(
            onDismissRequest = { viewingHistoryRecord = null },
            title = { Text("سجل تعديلات: ${record.title}") },
            text = {
                if (entries.isEmpty()) {
                    Text("لا توجد تعديلات مسجلة لهذا السجل.", color = Muted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.forEachIndexed { index, entry ->
                            Text("${index + 1}. ${entry}", color = Ink, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingHistoryRecord = null }) { Text("إغلاق") }
            }
        )
    }
    pendingDeleteId?.let { recordId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("تأكيد حذف السجل") },
            text = { Text("سيتم حذف هذا السجل من قاعدة البيانات المحلية. لا يمكن التراجع عن العملية.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(recordId)
                    pendingDeleteId = null
                }) { Text("حذف", color = Rose) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun Reports(all: List<HospitalRecord>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("التقارير والمؤشرات", color = Navy, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("ملخص السجلات المحفوظة على الجهاز", color = Muted) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي السجلات", color = Muted)
                    Text(all.size.toString(), color = Navy, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("سجل محلي", color = Teal, fontSize = 12.sp)
                }
            }
        }
        items(modules) { module ->
            val count = all.count { it.category == module.key }
            val ratio = if (all.isEmpty()) 0f else count.toFloat() / all.size.toFloat()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(module.color.copy(alpha = .12f), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(module.icon, contentDescription = null, tint = module.color)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(module.title, color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(module.subtitle, color = Muted, fontSize = 10.sp)
                        }
                        Surface(color = Mint, shape = RoundedCornerShape(10.dp)) {
                            Text(count.toString(), color = Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = module.color,
                        trackColor = Canvas
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(allCount: Int, exportStatus: String?, onBusinessProfile: () -> Unit, onReferenceCatalogs: () -> Unit, onExport: () -> Unit, onExportDatabase: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, Teal)), RoundedCornerShape(24.dp)).padding(22.dp)) {
                Column {
                    Icon(Icons.Default.LocalHospital, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("نظام المستشفى", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("نسخة تطبيق Android أولية • واجهة عربية RTL", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }
            }
        }
        item {
            SettingRow(Icons.Default.Storage, "قاعدة البيانات", "SQLite محلية عبر Room", Teal)
            SettingRow(Icons.Default.Language, "اللغة والاتجاه", "العربية • من اليمين إلى اليسار", Color(0xFF5B7CFA))
            SettingRow(Icons.Default.Palette, "التصميم", "Material 3 • ألوان هادئة وبطاقات حديثة", Color(0xFF9B72CF))
            SettingRow(Icons.Default.Storage, "السجلات على الجهاز", "$allCount سجل محفوظ", Color(0xFFDF8C42))
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Business, contentDescription = null, tint = Teal)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("بيانات المنشأة والنشاط التجاري", color = Navy, fontWeight = FontWeight.Bold)
                            Text("الاسم القانوني والسجل التجاري والتواصل والعملة.", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onBusinessProfile, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Business, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("إدارة بيانات المنشأة")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ListAlt, contentDescription = null, tint = Teal)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("القوائم المرجعية", color = Navy, fontWeight = FontWeight.Bold)
                            Text("إدارة الفحوصات والأدوية والتشخيصات والأقسام والخيارات الافتراضية المحفوظة في قاعدة البيانات.", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onReferenceCatalogs, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ListAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("إدارة القوائم المرجعية")
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = Teal)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("تصدير نسخة من السجلات", color = Navy, fontWeight = FontWeight.Bold)
                            Text("CSV بترميز UTF-8 لفتح البيانات العربية في الجداول.", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("تصدير السجلات العامة إلى CSV")
                    }
                    OutlinedButton(onClick = onExportDatabase, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("تصدير جميع جداول قاعدة البيانات إلى JSON")
                    }
                    exportStatus?.let { Text(it, color = if (it.startsWith("تم تصدير")) Teal else Rose, fontSize = 12.sp) }
                    Text("ملف JSON يشمل الجداول المحلية المتخصصة والقوائم المرجعية وسجل التدقيق. هو نسخة تصدير للقراءة والأرشفة وليس أداة استعادة تلقائية، ولا يشمل ملفات مرفقة أو بيانات الخادم غير المتزامنة.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8EC)),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0E5D2))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("مهم قبل الاستخدام الحقيقي", color = Color(0xFF87520E), fontWeight = FontWeight.Bold)
                    Text("هذه النسخة تحفظ السجلات محلياً على الجهاز. لم يُنفّذ بعد تسجيل دخول آمن أو مزامنة مع خادم Python أو نقل بيانات قاعدة المستشفى الحالية. لا تستخدم بيانات مرضى حقيقية قبل استكمال الأمان والاختبارات والتحقق السريري.", color = Color(0xFF805C20), fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, color: Color) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).background(color.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
