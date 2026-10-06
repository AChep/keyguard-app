package com.artemchep.keyguard.apple.model

/** Deterministic identities, including collisions with a source's literal # suffixes. */
internal fun uniqueListIds(keys: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    val used = mutableSetOf<String>()
    return keys.map { key ->
        var occurrence = occurrences[key] ?: 0
        var id: String
        do {
            id = if (occurrence == 0) key else "$key#$occurrence"
            occurrence += 1
        } while (!used.add(id))
        occurrences[key] = occurrence
        id
    }
}
