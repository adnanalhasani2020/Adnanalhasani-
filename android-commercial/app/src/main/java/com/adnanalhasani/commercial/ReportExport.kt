package com.adnanalhasani.commercial

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a valid XLSX OOXML workbook; Arabic text is stored as UTF-8 inline strings. */
internal fun writeReportXlsx(output: java.io.OutputStream, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات لتصديرها." }
    fun xml(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) append(ch)
            }
        }
    }
    fun colName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; out.append(('A'.code + rem).toChar()); n = (n - 1) / 26 }
        return out.reverse().toString()
    }
    val sheet = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews><sheetData>""")
        rows.forEachIndexed { ri, row ->
            append("<row r=\"").append(ri + 1).append("\">")
            row.forEachIndexed { ci, cell ->
                append("<c r=\"").append(colName(ci)).append(ri + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(xml(cell))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="التقرير" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        entry("xl/worksheets/sheet1.xml", sheet)
    }
}

/** Writes a paginated PDF report using Android's native PDF renderer. */
internal fun writeReportPdf(output: java.io.OutputStream, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات لتصديرها." }
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val left = 36f
    val right = pageWidth - 36f
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 48, 44)
        textSize = 10f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(22, 91, 72)
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(210, 222, 216)
        strokeWidth = 1f
    }
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = 0f
    fun startPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page!!.canvas
        canvas!!.drawColor(android.graphics.Color.WHITE)
        y = 42f
        canvas!!.drawText("تقرير متجري", right, y, titlePaint)
        y += 22f
        canvas!!.drawLine(left, y, right, y, rulePaint)
        y += 20f
    }
    fun finishPage() {
        val current = page ?: return
        canvas?.drawText("صفحة $pageNumber", right, (pageHeight - 22).toFloat(), bodyPaint)
        document.finishPage(current)
        page = null
        canvas = null
    }
    try {
        startPage()
        rows.forEach { row ->
            val text = row.joinToString("    |    ") { it.replace('\n', ' ').replace('\r', ' ') }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, (right - left).toInt())
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .build()
            if (y + layout.height > pageHeight - 54f) { finishPage(); startPage() }
            canvas!!.save()
            canvas!!.translate(left, y)
            layout.draw(canvas!!)
            canvas!!.restore()
            y += layout.height + 3f
        }
        finishPage()
        document.writeTo(output)
    } finally { document.close() }
}
/** Opens Android's system print preview and streams the same PDF report into the print spooler. */
internal fun printReport(context: Context, title: String, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات للطباعة." }
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: throw IllegalStateException("خدمة الطباعة غير متاحة على هذا الجهاز.")
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?
        ) {
            if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder("$title.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }
        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback
        ) {
            if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return }
            try {
                FileOutputStream(destination.fileDescriptor).use { writeReportPdf(it, rows) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (failure: Exception) {
                callback.onWriteFailed(failure.message ?: "تعذر إنشاء ملف الطباعة.")
            }
        }
    }
    printManager.print(title, adapter, PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build())
}
