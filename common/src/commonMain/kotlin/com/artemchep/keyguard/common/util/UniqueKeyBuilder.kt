package com.artemchep.keyguard.common.util

internal class UniqueKeyBuilder(
    private val format: (String, Int) -> String = { key, occurrence ->
        if (occurrence == 0) key else "$key#$occurrence"
    },
) {
    private val occurrences = mutableMapOf<String, Int>()
    private val usedKeys = mutableSetOf<String>()

    fun build(key: String): String {
        var occurrence = occurrences[key] ?: 0
        while (true) {
            val result = format(key, occurrence)
            occurrence += 1
            if (usedKeys.add(result)) {
                occurrences[key] = occurrence
                return result
            }
        }
    }
}
