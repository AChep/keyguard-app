package com.artemchep.keyguard.util.dns

/**
 * Undoes the quoting that the JDK DNS provider applies to TXT RDATA. The
 * character-strings are joined with a single space, and a string is wrapped
 * in double quotes when it is empty or contains a space, a quote or a
 * backslash, with those two escaped by a backslash. Bytes are mapped to chars
 * one to one, so the text is re-encoded as ISO-8859-1 and decoded as UTF-8.
 */
internal fun decodeJndiTxtValue(value: String): String {
    val builder = StringBuilder(value.length)
    var quoted = false
    var escaped = false
    for (char in value) {
        when {
            escaped -> {
                builder.append(char)
                escaped = false
            }

            char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            char == ' ' && !quoted -> Unit
            else -> builder.append(char)
        }
    }
    return builder.toString()
        .toByteArray(Charsets.ISO_8859_1)
        .decodeToString()
}
