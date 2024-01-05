package com.artemchep.keyguard.common.service.placeholder.util

class Parser(
    private val name: String,
    private val count: Int,
) {
    private val prefix = "$name:"

    fun parse(key: String): ParserResult? {
        val handles = key.startsWith(prefix, ignoreCase = true)
        if (!handles) {
            return null
        }

        val separator = key.getOrNull(prefix.length)
            ?: return null // a separator must be defined!
        val suffix = key.substring(prefix.length + 1)
        val suffixA = suffix
            .split(separator)
        if (suffixA.size < count) {
            return null
        }
        val value = suffixA
            .dropLast(count)
            .joinToString(separator = "")
        return ParserResult(
            value = value,
            params = suffixA
                .takeLast(count)
                .dropLast(1),
        )
    }
}

data class ParserResult(
    val value: String,
    val params: List<String>,
)
