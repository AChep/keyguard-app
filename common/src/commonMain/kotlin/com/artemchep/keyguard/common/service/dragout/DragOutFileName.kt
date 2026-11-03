package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.service.download.store.downloadDisplayFileName
import com.artemchep.keyguard.common.util.claimNumberedName

/** A file name every drag recipient accepts, including Windows shell targets. */
internal fun dragOutFileName(name: String): String {
    val portable = name.map { if (it in "<>\"|?*") '_' else it }.joinToString("")
    // Windows strips trailing dots and spaces, in any order.
    val safe = downloadDisplayFileName(portable)
        .withoutUnpairedSurrogates()
        .trimEnd('.', ' ')
        .ifEmpty { "attachment" }
    val stem = safe.substringBefore('.').trimEnd(' ').map { if (it in 'a'..'z') it.uppercaseChar() else it }
        .joinToString("")
    return if (stem in WINDOWS_RESERVED_NAMES) "_$safe" else safe
}

/** The byte-length trim can split a surrogate pair; recipients reject the remainder. */
private fun String.withoutUnpairedSurrogates(): String = buildString(length) {
    var index = 0
    while (index < this@withoutUnpairedSurrogates.length) {
        val char = this@withoutUnpairedSurrogates[index]
        val next = this@withoutUnpairedSurrogates.getOrNull(index + 1)
        if (char.isHighSurrogate() && next?.isLowSurrogate() == true) {
            append(char).append(next)
            index += 2
        } else {
            append(if (char.isSurrogate()) '_' else char)
            index += 1
        }
    }
}

/** Device names Windows reserves, with or without an extension. */
private val WINDOWS_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") +
    listOf("COM", "LPT").flatMap { prefix ->
        (('0'..'9').map(Char::toString) + listOf("¹", "²", "³")).map { prefix + it }
    }

/**
 * Makes names unique ignoring case, as one drop into a folder requires:
 * `a.txt`, `A.txt` becomes `a.txt`, `A (2).txt`. Both case folds count, since
 * the native bridges compare differently.
 */
internal fun distinctDragOutFileNames(names: List<String>): List<String> {
    val taken = mutableSetOf<String>()
    return names.map { name ->
        taken.claimNumberedName(name, firstNumber = 2) { setOf(it.uppercase(), it.lowercase()) }
    }
}
