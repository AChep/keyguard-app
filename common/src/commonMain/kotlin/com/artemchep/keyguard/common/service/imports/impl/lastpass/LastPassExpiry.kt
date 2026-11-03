package com.artemchep.keyguard.common.service.imports.impl.lastpass

import com.artemchep.keyguard.common.service.imports.mapping.ImportedExpiry
import com.artemchep.keyguard.common.service.imports.mapping.importedExpiryOrNull

private val englishMonths = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)
private val yearPattern = Regex("[0-9]{2}|[0-9]{4}")

/**
 * LastPass allows either part of `<month name>,<year>` to be empty.
 * A nonempty invalid part rejects the whole date so its source field survives.
 */
internal fun parseLastPassExpiry(raw: String): ImportedExpiry? = if (',' !in raw) {
    importedExpiryOrNull(raw)
} else {
    raw.split(',').takeIf { it.size == 2 }?.let { parts ->
        val (monthText, yearText) = parts.map { it.trim() }
        val month = englishMonths.indexOf(monthText.lowercase()) + 1
        when {
            monthText.isNotEmpty() && month == 0 -> null
            yearText.isNotEmpty() && (!yearPattern.matches(yearText) || yearText == "0000") -> null
            else -> ImportedExpiry(
                month = month.takeIf { it != 0 }?.toString(),
                year = yearText.takeIf { it.isNotEmpty() }?.let {
                    if (it.length == 2) "20$it" else it
                },
            )
        }
    }
}
