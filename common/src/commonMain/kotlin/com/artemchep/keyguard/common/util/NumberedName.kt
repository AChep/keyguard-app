package com.artemchep.keyguard.common.util

/**
 * Claims [name] in this set or, when it is taken, the first free
 * `stem (n).extension` counting up from [firstNumber], and returns the claimed
 * name. The extension starts at the last dot unless that dot begins the name.
 *
 * A candidate is free only when none of its [keys] is in the set; all of them
 * are added on claim. The default compares names exactly.
 */
internal fun MutableSet<String>.claimNumberedName(
    name: String,
    firstNumber: Int,
    keys: (String) -> Set<String> = { setOf(it) },
): String {
    fun claim(candidate: String): Boolean {
        val candidateKeys = keys(candidate)
        if (candidateKeys.any { it in this }) return false
        addAll(candidateKeys)
        return true
    }
    if (claim(name)) return name
    val stem = name.fileNameStem()
    val extension = name.substring(stem.length)
    return generateSequence(firstNumber) { it + 1 }
        .map { number -> "$stem ($number)$extension" }
        .first(::claim)
}
