package com.adnanalhasani.hospital

/**
 * Creates a spreadsheet-friendly CSV snapshot of the local records.
 *
 * The UTF-8 BOM is added by the file-writing caller so Arabic text opens
 * correctly in common spreadsheet applications.
 */
object HospitalExport {
    private val headers = listOf("id", "category", "title", "details", "created_at")

    fun toCsv(records: List<HospitalRecord>): String = buildString {
        appendLine(headers.joinToString(",") { escape(it) })
        records.sortedBy { it.id }.forEach { record ->
            appendLine(
                listOf(
                    record.id.toString(),
                    record.category,
                    record.title,
                    record.details,
                    record.createdAt.toString()
                ).joinToString(",") { escape(it) }
            )
        }
    }

    private fun escape(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace("\r", "\n")
        // Prevent spreadsheet formula execution when a user-controlled cell starts
        // with a formula marker, even when the marker follows whitespace.
        val firstMeaningful = normalized.trimStart().firstOrNull()
        val spreadsheetSafe = if (firstMeaningful != null && firstMeaningful in listOf('=', '+', '-', '@', '\t')) {
            "'$normalized"
        } else {
            normalized
        }
        return if (spreadsheetSafe.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + spreadsheetSafe.replace("\"", "\"\"") + "\""
        } else {
            spreadsheetSafe
        }
    }
}
