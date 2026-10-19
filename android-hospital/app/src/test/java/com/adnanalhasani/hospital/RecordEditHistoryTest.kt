package com.adnanalhasani.hospital

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordEditHistoryTest {
    @Test
    fun appendsHistoryWithoutPuttingOldHistoryInEditableDetails() {
        val original = "ملاحظة أولى\n\nسجل التعديلات:\n- 2026-10-19 10:00:00: تم تعديل السجل"
        val edited = RecordEditHistory.append("ملاحظة جديدة", original, "2026-10-20 11:30:00")

        assertEquals("ملاحظة جديدة", RecordEditHistory.visibleDetails(edited))
        assertEquals(
            listOf(
                "- 2026-10-19 10:00:00: تم تعديل السجل",
                "- 2026-10-20 11:30:00: تم تعديل السجل"
            ),
            RecordEditHistory.entries(edited)
        )
    }

    @Test
    fun preservesHistoryWhenDetailsAreEmpty() {
        val original = "تفاصيل قديمة\n\nسجل التعديلات:\n- وقت سابق: تم تعديل السجل"
        val edited = RecordEditHistory.append("", original, "وقت جديد")

        assertEquals("", RecordEditHistory.visibleDetails(edited))
        assertEquals(2, RecordEditHistory.entries(edited).size)
    }

    @Test
    fun recordsSpecificActionsInAuditHistory() {
        val updated = RecordEditHistory.append(
            "حالة الحالة: تحويل",
            "حالة الحالة: قيد العلاج",
            "2026-10-20 12:00:00",
            "تم تغيير حالة الطوارئ إلى تحويل"
        )

        assertEquals(
            listOf("- 2026-10-20 12:00:00: تم تغيير حالة الطوارئ إلى تحويل"),
            RecordEditHistory.entries(updated)
        )
        assertEquals("حالة الحالة: تحويل", RecordEditHistory.visibleDetails(updated))
    }

    @Test
    fun safelyReadsRecordsWithoutHistory() {
        assertEquals("تفاصيل عادية", RecordEditHistory.visibleDetails("تفاصيل عادية"))
        assertEquals(emptyList<String>(), RecordEditHistory.entries("تفاصيل عادية"))
    }
}
