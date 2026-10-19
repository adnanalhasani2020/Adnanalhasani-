package com.adnanalhasani.hospital

import org.junit.Assert.assertEquals
import org.junit.Test

class HospitalExportTest {
    @Test
    fun exportsHeaderAndSortsRecordsById() {
        val csv = HospitalExport.toCsv(
            listOf(
                HospitalRecord(id = 9, category = "patients", title = "سارة", details = "", createdAt = 2),
                HospitalRecord(id = 2, category = "doctors", title = "طبيب", details = "", createdAt = 1)
            )
        )

        assertEquals(
            "id,category,title,details,created_at\n" +
                "2,doctors,طبيب,,1\n" +
                "9,patients,سارة,,2\n",
            csv
        )
    }

    @Test
    fun quotesCommasQuotesAndNewlinesAccordingToCsvRules() {
        val csv = HospitalExport.toCsv(
            listOf(
                HospitalRecord(
                    id = 1,
                    category = "patients",
                    title = "اسم, مريض",
                    details = "ملاحظة \"مهمة\"\nالسطر الثاني",
                    createdAt = 10
                )
            )
        )

        assertEquals(
            "id,category,title,details,created_at\n" +
                "1,patients,\"اسم, مريض\",\"ملاحظة \"\"مهمة\"\"\nالسطر الثاني\",10\n",
            csv
        )
    }

    @Test
    fun prefixesSpreadsheetFormulaMarkersInUserControlledCells() {
        val csv = HospitalExport.toCsv(
            listOf(
                HospitalRecord(id = 1, category = "patients", title = "  =1+1", details = "@SUM(A1:A2)", createdAt = 10)
            )
        )

        assertEquals(
            "id,category,title,details,created_at\\n" +
                "1,patients,'  =1+1,'@SUM(A1:A2),10\\n",
            csv
        )
    }

    @Test
    fun emptyExportStillContainsHeader() {
        assertEquals("id,category,title,details,created_at\n", HospitalExport.toCsv(emptyList()))
    }
}
