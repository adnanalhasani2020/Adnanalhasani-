package com.adnanalhasani.hospital

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch
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
        val dao = HospitalDatabase.get(this).hospitalDao()
        setContent { HospitalApp(dao) }
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
    Module("appointments", "المواعيد", "تنظيم المواعيد والحجوزات", Icons.Default.CalendarMonth, Color(0xFF9A91B8),
        listOf("اسم المريض" to "اسم المريض", "اسم الطبيب" to "اسم الطبيب", "التاريخ والوقت" to "مثال: 2026-10-20 10:30", "سبب الزيارة" to "سبب الزيارة")),
    Module("encounters", "الزيارات الطبية", "متابعة الزيارة والتشخيص", Icons.Default.MedicalInformation, Color(0xFF6F9BAE),
        listOf("اسم المريض" to "اسم المريض", "اسم الطبيب" to "اسم الطبيب", "الشكوى الرئيسية" to "الشكوى", "التشخيص والملاحظات" to "التشخيص والملاحظات")),
    Module("lab", "المختبر", "طلبات الفحوصات والنتائج", Icons.Default.Science, Color(0xFFD0A06E),
        listOf("اسم المريض" to "اسم المريض", "اسم الفحص" to "نوع الفحص", "حالة الفحص" to "مطلوب / قيد التنفيذ / مكتمل", "النتيجة" to "النتيجة أو ملاحظات")),
    Module("pharmacy", "الصيدلية والأدوية", "الأدوية والوصفات والمخزون", Icons.Default.Medication, Color(0xFF6D9D86),
        listOf("اسم الدواء" to "اسم الدواء", "رمز الدواء" to "رمز أو SKU", "الكمية" to "الكمية المتوفرة", "الجرعة / الوصفة" to "الجرعة والتعليمات")),
    Module("admissions", "التنويم والأسرة", "إدارة الأسرة والدخول والخروج", Icons.Default.Hotel, Color(0xFF6D82C4),
        listOf("اسم المريض" to "اسم المريض", "القسم / الجناح" to "القسم", "رقم السرير" to "رقم السرير", "حالة التنويم" to "منوّم / خرج", "التاريخ" to "تاريخ الدخول أو الخروج")),
    Module("invoices", "الفواتير", "إنشاء ومتابعة الفواتير", Icons.Default.ReceiptLong, Color(0xFFD0A06E),
        listOf("رقم الفاتورة" to "رقم الفاتورة", "اسم المريض" to "اسم المريض", "المبلغ" to "المبلغ", "العملة" to "YER", "الحالة" to "مفتوحة / مدفوعة")),
    Module("payments", "المدفوعات", "تسجيل الدفعات والإيصالات", Icons.Default.Payments, Color(0xFF548F88),
        listOf("رقم الفاتورة" to "رقم الفاتورة", "المبلغ المدفوع" to "المبلغ", "طريقة الدفع" to "نقداً / تحويل", "مرجع الإيصال" to "رقم الإيصال")),
    Module("prescriptions", "الوصفات الطبية", "وصفات المرضى وتعليمات الدواء", Icons.Default.Description, Color(0xFFBD6D9D),
        listOf("اسم المريض" to "اسم المريض", "اسم الدواء" to "اسم الدواء", "الجرعة" to "الجرعة", "التكرار والمدة" to "التكرار والمدة", "الحالة" to "معلقة / صُرفت")),
    Module("beds", "إدارة الأسرة", "تسجيل الأسرة وحالتها", Icons.Default.Hotel, Color(0xFF5986A4),
        listOf("الجناح" to "اسم الجناح", "رقم السرير" to "رقم السرير", "الحالة" to "متاح / مشغول", "ملاحظات" to "ملاحظات")),
    Module("users", "المستخدمون", "سجل إداري محلي للمستخدمين", Icons.Default.AdminPanelSettings, Color(0xFF7C78C9),
        listOf("اسم المستخدم" to "اسم المستخدم", "الدور" to "مدير / طبيب / استقبال", "الحالة" to "نشط / غير نشط")),
    Module("nursing", "التمريض والعلامات الحيوية", "متابعة القياسات والرعاية التمريضية", Icons.Default.MedicalInformation, Color(0xFF277DA1),
        listOf("اسم المريض" to "اسم المريض", "الضغط" to "مثال: 120/80", "الحرارة" to "درجة الحرارة", "النبض" to "نبضة/دقيقة", "التنفس" to "نفس/دقيقة", "ملاحظات التمريض" to "ملاحظات")),
    Module("radiology", "الأشعة والتصوير", "طلبات الأشعة وتقارير النتائج", Icons.Default.Science, Color(0xFF6C63A8),
        listOf("اسم المريض" to "اسم المريض", "نوع التصوير" to "أشعة / CT / MRI / موجات فوق صوتية", "الطبيب الطالب" to "اسم الطبيب", "الحالة" to "مطلوب / قيد التنفيذ / مكتمل", "التقرير" to "النتيجة أو التقرير")),
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HospitalApp(dao: HospitalDao) {
    var selected by remember { mutableStateOf("home") }
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val all by dao.allRecords().collectAsState(initial = emptyList())
    val currentModule = modules.find { it.key == selected }
    val screenTitle = currentModule?.title ?: when (selected) {
        "home" -> "لوحة التحكم"
        "reports" -> "التقارير والمؤشرات"
        "settings" -> "الإعدادات"
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
                    selected == "settings" -> SettingsScreen(allCount = all.size)
                    currentModule != null -> ModuleScreen(
                        module = currentModule,
                        records = all.filter { it.category == currentModule.key },
                        onSave = { title, details ->
                            dao.saveRecord(HospitalRecord(category = currentModule.key, title = title, details = details))
                        },
                        onDelete = { id -> scope.launch { dao.delete(id) } }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xFF16365B), Color(0xFF087E82))),
                    RoundedCornerShape(26.dp)
                ).padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Color.White.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.LocalHospital, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("مرحباً بك 👋", color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
                            Text("إدارة المستشفى بذكاء", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("كل أقسام المستشفى في مكان واحد، بواجهة عربية واضحة وسريعة.", color = Color.White.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 21.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = Color.White.copy(alpha = .15f), shape = RoundedCornerShape(12.dp)) {
                            Text("قاعدة محلية SQLite", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                        }
                        Surface(color = Color.White.copy(alpha = .15f), shape = RoundedCornerShape(12.dp)) {
                            Text("واجهة عربية RTL", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                        }
                    }
                }
            }
        }
        item {
            Text("نظرة عامة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(10.dp))
            val metrics = listOf(
                Triple("المرضى", all.count { it.category == "patients" }, Color(0xFF0D9488)),
                Triple("المواعيد", all.count { it.category == "appointments" }, Color(0xFF5B7CFA)),
                Triple("طلبات المختبر", all.count { it.category == "lab" }, Color(0xFFDF8C42)),
                Triple("الفواتير", all.count { it.category == "invoices" }, Color(0xFF9B72CF))
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                metrics.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, count, color) ->
                            Card(Modifier.weight(1f), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(label, color = Muted, fontSize = 12.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text(count.toString(), color = Navy, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Box(Modifier.padding(top = 8.dp).width(36.dp).height(4.dp).background(color, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("الأقسام والخدمات", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("عرض الكل", color = Teal, fontSize = 12.sp, modifier = Modifier.clickable { onSelect("menu") })
            }
        }
        items(modules.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { module ->
                    Card(
                        modifier = Modifier.weight(1f).clickable { onSelect(module.key) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(44.dp).background(module.color.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Icon(module.icon, null, tint = module.color, modifier = Modifier.size(24.dp))
                            }
                            Text(module.title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(module.subtitle, color = Muted, fontSize = 10.sp, lineHeight = 15.sp, minLines = 2)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E4)), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFFB77816))
                    Spacer(Modifier.width(10.dp))
                    Text("هذه نسخة تأسيسية محلية. بيانات السجلات تُحفظ على هذا الجهاز في SQLite، ولا تتزامن تلقائياً مع قاعدة Python الموجودة على الخادم.", color = Color(0xFF805C20), fontSize = 12.sp, lineHeight = 18.sp)
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

@Composable
private fun ModuleScreen(
    module: Module,
    records: List<HospitalRecord>,
    onSave: suspend (String, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var values by remember(module.key) { mutableStateOf(module.fields.associate { it.first to "" }) }
    var savedMessage by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(module.key) { mutableStateOf("") }
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
                    Text("إضافة سجل جديد", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    module.fields.forEach { (label, hint) ->
                        OutlinedTextField(
                            value = values[label].orEmpty(),
                            onValueChange = { values = values + (label to it); savedMessage = false; validationMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) },
                            placeholder = { Text(hint, fontSize = 12.sp) },
                            shape = RoundedCornerShape(14.dp),
                            singleLine = label !in listOf("ملاحظات", "التشخيص والملاحظات", "النتيجة", "الجرعة / الوصفة"),
                            keyboardOptions = if (label.contains("مبلغ") || label == "الكمية" || label == "العمر") KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default
                        )
                    }
                    Button(
                        onClick = {
                            val titleField = module.fields.firstOrNull()?.first
                            val title = titleField?.let { values[it]?.trim() }.orEmpty()
                            if (title.isBlank()) {
                                validationMessage = "أدخل ${titleField ?: "البيانات المطلوبة"} قبل الحفظ."
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
                                    validationMessage = "تعذر حفظ السجل. حاول مرة أخرى."
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
                            if (record.details.isNotBlank()) Text(record.details, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            Text(SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault()).format(Date(record.createdAt)), color = Muted.copy(alpha = .8f), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        IconButton(onClick = { onDelete(record.id) }) { Icon(Icons.Default.DeleteOutline, null, tint = Rose) }
                    }
                }
            }
        }
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
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(module.icon, null, tint = module.color)
                Spacer(Modifier.width(12.dp))
                Text(module.title, color = Navy, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(count.toString(), color = Teal, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(allCount: Int) {
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
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2E2)), shape = RoundedCornerShape(18.dp)) {
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
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(color.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}
