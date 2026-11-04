package com.adnanalhasani.commercial

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.io.OutputStreamWriter

internal data class ReportsExportLaunchers(
    val csv: ActivityResultLauncher<String>,
    val xlsx: ActivityResultLauncher<String>,
    val pdf: ActivityResultLauncher<String>
)

/** Owns Android document-picker callbacks and export I/O for the reports center. */
@Composable
internal fun rememberReportsExportLaunchers(
    context: Context,
    dateError: Boolean,
    cloudMode: Boolean,
    cloudLoading: Boolean,
    selectedReportSourceError: String,
    exportRows: List<List<String>>,
    onError: (String) -> Unit
): ReportsExportLaunchers {
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && selectedReportSourceError.isBlank())) { "تعذر التصدير لأن مصدر هذا التقرير غير مكتمل." }
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write("\uFEFF")
                    writer.write(exportRows.joinToString("\r\n") { row -> row.joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" } })
                }
            } ?: throw IllegalStateException("تعذر فتح ملف التصدير.")
        }.onFailure { onError("تعذر تصدير التقرير: ${it.message ?: "خطأ غير معروف"}") }
    }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && selectedReportSourceError.isBlank())) { "تعذر التصدير لأن مصدر هذا التقرير غير مكتمل." }
            context.contentResolver.openOutputStream(uri)?.use { writeReportXlsx(it, exportRows) }
                ?: throw IllegalStateException("تعذر فتح ملف Excel.")
        }.onFailure { onError("تعذر تصدير XLSX: ${it.message ?: "خطأ غير معروف"}") }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && selectedReportSourceError.isBlank())) { "تعذر التصدير لأن مصدر هذا التقرير غير مكتمل." }
            context.contentResolver.openOutputStream(uri)?.use { writeReportPdf(it, exportRows) }
                ?: throw IllegalStateException("تعذر فتح ملف PDF.")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير متجري"))
        }.onFailure { onError("تعذر إنشاء أو مشاركة PDF: ${it.message ?: "خطأ غير معروف"}") }
    }
    return ReportsExportLaunchers(csv, xlsx, pdf)
}
