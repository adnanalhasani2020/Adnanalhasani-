package com.adnanalhasani.hospital

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonEscapingTest {
    @Test
    fun escapesQuotesBackslashesAndJsonControlCharacters() {
        assertEquals(
            "quote=\\\" slash=\\\\ newline=\\n tab=\\t backspace=\\b formfeed=\\f control=\\u0001",
            escapeJsonString("quote=\" slash=\\ newline=\n tab=\t backspace=\b formfeed=\u000C control=\u0001")
        )
    }

    @Test
    fun preservesOrdinaryUnicodeText() {
        assertEquals("مستشفى — patient", escapeJsonString("مستشفى — patient"))
    }
}
