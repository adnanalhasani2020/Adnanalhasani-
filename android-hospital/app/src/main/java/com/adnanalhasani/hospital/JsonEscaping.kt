package com.adnanalhasani.hospital

/**
 * Escapes a string for embedding inside a JSON string literal.
 * Every U+0000–U+001F character must be escaped by the JSON grammar.
 */
internal fun escapeJsonString(raw: String): String = buildString(raw.length) {
    raw.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
