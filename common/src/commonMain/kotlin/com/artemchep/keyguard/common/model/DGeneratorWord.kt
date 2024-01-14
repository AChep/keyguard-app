package com.artemchep.keyguard.common.model

data class DGeneratorWord(
    val id: String? = null,
    val word: String,
) : Comparable<DGeneratorWord> {
    override fun compareTo(other: DGeneratorWord): Int {
        return word.compareTo(other.word, ignoreCase = true)
    }
}
