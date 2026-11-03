package com.adnanalhasani.commercial

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportTest {
    @Test
    fun xlsxExportProducesRtlWorkbookWithArabicContent() {
        val output = ByteArrayOutputStream()
        writeReportXlsx(output, listOf(listOf("الصنف", "الإجمالي"), listOf("قهوة", "1500")))

        val entries = linkedMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        val sheet = entries["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(sheet.contains("rightToLeft=\"1\""))
        assertTrue(sheet.contains("الصنف"))
        assertTrue(sheet.contains("قهوة"))
        assertTrue(sheet.contains("1500"))
    }

    @Test
    fun xlsxExportEscapesXmlCharactersAndSupportsColumnsBeyondZ() {
        val output = ByteArrayOutputStream()
        val row = (0..26).map { index -> if (index == 0) "A&B <Tea>" else "Column $index" }
        writeReportXlsx(output, listOf(row))

        val entries = linkedMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }

        val sheet = entries["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(sheet.contains("A&amp;B &lt;Tea&gt;"))
        assertTrue(sheet.contains("Z1"))
        assertTrue(sheet.contains("AA1"))
        assertTrue(sheet.contains("Column 26"))
    }

    @Test
    fun xlsxExportRejectsEmptyRows() {
        val output = ByteArrayOutputStream()
        try {
            writeReportXlsx(output, emptyList())
            throw AssertionError("Expected empty report export to fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals("لا توجد بيانات لتصديرها.", expected.message)
        }
    }
}
