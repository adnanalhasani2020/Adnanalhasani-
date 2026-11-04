package com.adnanalhasani.commercial

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Adds report category and catalog rows to the parent LazyColumn. */
internal fun LazyListScope.reportsCatalogSection(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    filteredCatalog: List<ReportCatalogItem>,
    selectedReport: String,
    dateError: Boolean,
    cloudMode: Boolean,
    cloudLoading: Boolean,
    sourceErrorForReport: (String) -> String,
    supplierPaymentsLoadError: String,
    stockMovementsLoadError: String,
    stockAlertsLoadError: String,
    locationInventoryLoadError: String,
    onReportSelected: (String) -> Unit
) {
    item {
        Text("تصنيفات التقارير", fontWeight = FontWeight.Bold, color = ReportInk, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = selectedCategory == "الكل", onClick = { onCategorySelected("الكل") }, label = { Text("الكل") }) }
            items(categories) { category ->
                FilterChip(selected = selectedCategory == category, onClick = { onCategorySelected(category) }, label = { Text(category, fontSize = 10.sp) })
            }
        }
    }
    item {
        Text("التقارير المتاحة", fontWeight = FontWeight.Bold, color = ReportInk, fontSize = 17.sp)
        Text("لا يظهر التقرير كمتاح إلا عندما يكون مصدر البيانات الموجود كافيًا لحساب النتيجة المعروضة.", fontSize = 11.sp, color = ReportMuted)
    }
    items(filteredCatalog, key = { it.title }) { report ->
        val selected = selectedReport == report.title
        Card(
            modifier = Modifier.fillMaxWidth().clickable(
                enabled = report.supported && !dateError &&
                    (!cloudMode || (!cloudLoading && sourceErrorForReport(report.title).isBlank())),
                onClick = { selectableReportTitle(report.title)?.let(onReportSelected) }
            ),
            colors = CardDefaults.cardColors(containerColor = if (selected) ReportMint else Color.White),
            border = BorderStroke(1.dp, if (selected) ReportGreen else ReportBorder),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(report.title, fontWeight = FontWeight.SemiBold, color = ReportInk)
                        Text(report.category, color = ReportMuted, fontSize = 11.sp)
                    }
                    val sourceReady = report.supported &&
                        (!cloudMode || (!cloudLoading && sourceErrorForReport(report.title).isBlank()))
                    Surface(
                        color = if (sourceReady) ReportMint else Color(0xFFFFF1DF),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            when {
                                !report.supported -> "يتطلب مصدرًا مكتملًا"
                                cloudMode && cloudLoading -> "جارٍ تحميل المصدر"
                                cloudMode && sourceErrorForReport(report.title).isNotBlank() -> "تعذر تحميل المصدر"
                                cloudMode && report.title == "كشف حركات الموردين" && supplierPaymentsLoadError.isNotBlank() -> "تعذر تحميل المصدر"
                                cloudMode && report.title == "حركة المخزون الفعلية" && stockMovementsLoadError.isNotBlank() -> "تعذر تحميل المصدر"
                                cloudMode && report.title in setOf("رصيد المخزون الحالي", "تنبيهات المخزون المنخفض") && stockAlertsLoadError.isNotBlank() -> "تعذر تحميل المصدر"
                                cloudMode && report.title == "رصيد المخزون حسب الموقع" && locationInventoryLoadError.isNotBlank() -> "تعذر تحميل المصدر"
                                else -> "متاح"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = if (sourceReady) ReportGreen else Color(0xFF9A5B12),
                            fontSize = 10.sp
                        )
                    }
                }
                Text(report.source, color = ReportMuted, fontSize = 11.sp)
                Text(report.note, color = ReportMuted, fontSize = 11.sp)
            }
        }
    }
}

private fun selectableReportTitle(title: String): String? = when (title) {
    "ملخص المبيعات حسب الفترة", "تفاصيل فواتير المبيعات", "تفاصيل بنود فواتير المبيعات", "المبيعات حسب الصنف",
    "المبيعات حسب العميل", "المبيعات النقدية والآجلة", "المبيعات حسب حالة السداد", "متوسط قيمة الفاتورة", "مقارنة فترتين", "مقارنة الفترات", "اتجاه المبيعات اليومي", "الخصومات على فواتير المبيعات", "الضرائب المسجلة على المبيعات",
    "التحصيلات المسجلة محليًا", "الدفعات السحابية الناجحة", "التحصيل حسب طريقة الدفع", "التحصيل حسب العميل", "المصروفات التشغيلية", "ملخص المصروفات حسب التصنيف وطريقة الصرف", "رصيد المخزون الحالي", "تنبيهات المخزون المنخفض",
    "فواتير الشراء والمستحقات المسجلة", "المشتريات حسب المورد", "فروقات أسعار فواتير الموردين", "مستحقات فواتير الموردين", "أعمار مستحقات الموردين", "عمر مستحقات الموردين حسب تاريخ الفاتورة", "مرتجعات المبيعات وحالة التسوية", "سندات القبض والصرف", "سجل القيود المحاسبية",
    "ميزان المراجعة حسب الحساب", "حركة دفتر الأستاذ العام", "كشف حساب محاسبي", "أرصدة العملاء وكشف الحساب", "كشف حركات الموردين",
    "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات", "قائمة الدخل والميزانية العمومية", "التدفقات النقدية والصندوق", "تكلفة البضاعة والربح", "ربحية الأصناف بعد المرتجعات", "الضرائب والخصومات التفصيلية", "ملخص الضرائب والخصومات",
    "حركة المخزون الفعلية", "حركة المخزون حسب المستخدم", "رصيد المخزون حسب الموقع", "التقييم المالي الحالي للمخزون", "تفاصيل القيود المرحلة" -> title
    "لوحة المؤشرات" -> "ملخص المبيعات حسب الفترة"
    else -> null
}
