package com.adnanalhasani.commercial

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun buildReportExportRows(
    exportTitle: String,
    startText: String,
    endText: String,
    cloudMode: Boolean,
    rows: List<ReportLine>
): List<List<String>> = buildList {
    add(listOf("التقرير", exportTitle))
    add(listOf("الفترة", if (exportTitle == "التقييم المالي الحالي للمخزون") "لقطة حالية للموقع المختار؛ ليست تاريخية" else if (exportTitle == "رصيد المخزون حسب الموقع" || exportTitle == "حالة ربط أرصدة المخزون بالمستودعات") "لقطة حالية لجميع مواقع المؤسسة؛ ليست تاريخية" else startText + " — " + endText))
    add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
    add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
    add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
    rows.forEach { row ->
        add(listOf(row.date, row.reference, row.name, reportLineAmount(row, cloudMode), row.currency, row.status))
    }
}

/** Adds result actions and report rows to the center's parent LazyColumn. */
internal fun LazyListScope.reportsResultSection(
    context: Context,
    selectedReport: String,
    selectedRows: List<ReportLine>,
    dateError: Boolean,
    cloudMode: Boolean,
    cloudLoading: Boolean,
    selectedReportSourceError: String,
    startText: String,
    endText: String,
    launchers: ReportsExportLaunchers,
    onExportStateChanged: (String, List<List<String>>) -> Unit,
    onError: (String) -> Unit
) {
    if (selectedReport != "ملخص المبيعات" && selectedRows.isNotEmpty()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selectedReport, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ReportInk)
                    Text("عدد السجلات: ${selectedRows.size}", fontSize = 11.sp, color = ReportMuted)
                }
                OutlinedButton(onClick = {
                    onExportStateChanged(selectedReport, buildReportExportRows(selectedReport, startText, endText, cloudMode, selectedRows))
                    launchers.csv.launch("mutajri-report-${selectedReport.hashCode()}.csv")
                }) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("تصدير CSV") }
                OutlinedButton(onClick = {
                    onExportStateChanged(selectedReport, buildReportExportRows(selectedReport, startText, endText, cloudMode, selectedRows))
                    launchers.xlsx.launch("mutajri-report-${selectedReport.hashCode()}.xlsx")
                }) { Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(4.dp)); Text("Excel XLSX") }
                OutlinedButton(onClick = {
                    if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) {
                        onError("لا يمكن تصدير التقرير: تحقق من الفترة واكتمال مصادر هذا التقرير.")
                    } else {
                        onExportStateChanged(selectedReport, buildReportExportRows(selectedReport, startText, endText, cloudMode, selectedRows))
                        launchers.pdf.launch("mutajri-report-${selectedReport.hashCode()}.pdf")
                    }
                }) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(4.dp)); Text("PDF ومشاركة") }
                OutlinedButton(onClick = {
                    if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) {
                        onError("لا يمكن طباعة التقرير: تحقق من الفترة واكتمال مصادر هذا التقرير.")
                    } else {
                        val rows = buildReportExportRows(selectedReport, startText, endText, cloudMode, selectedRows)
                        onExportStateChanged(selectedReport, rows)
                        runCatching { printReport(context, selectedReport, rows) }
                            .onFailure { onError("تعذرت معاينة الطباعة: ${it.message ?: "خطأ غير معروف"}") }
                    }
                }) { Icon(Icons.Default.Print, null); Spacer(Modifier.width(4.dp)); Text("معاينة وطباعة") }
            }
        }
        items(selectedRows) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, ReportBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, fontWeight = FontWeight.SemiBold, color = ReportInk)
                    Text("${row.reference} · ${row.date}", color = ReportMuted, fontSize = 11.sp)
                    Text(reportLineAmount(row, cloudMode), color = ReportGreen, fontWeight = FontWeight.Bold)
                    if (row.status.isNotBlank()) Text(row.status, color = ReportMuted, fontSize = 11.sp)
                }
            }
        }
    } else if (selectedReport != "ملخص المبيعات" && !dateError) {
        item { Text("لا توجد بيانات لهذا التقرير ضمن الفترة المحددة.", color = ReportMuted, modifier = Modifier.padding(8.dp)) }
    }
}
