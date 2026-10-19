package com.adnanalhasani.hospital

/**
 * Keeps edit history separate from the editable details field while preserving
 * the existing on-disk representation for compatibility with older records.
 */
object RecordEditHistory {
    private const val MARKER = "\n\nسجل التعديلات:\n"

    fun visibleDetails(storedDetails: String): String =
        storedDetails.substringBefore(MARKER).trimEnd()

    fun entries(storedDetails: String): List<String> =
        storedDetails.substringAfter(MARKER, "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    /**
     * Appends a history entry to the updated details, preserving the previous
     * history even when a caller only passes the visible details for editing.
     */
    fun append(storedDetails: String, previousStoredDetails: String, timestamp: String): String {
        val cleanDetails = visibleDetails(storedDetails)
        val nextEntry = "- $timestamp: تم تعديل السجل"
        val allEntries = entries(previousStoredDetails) + nextEntry
        return cleanDetails + MARKER + allEntries.joinToString("\n")
    }
}
