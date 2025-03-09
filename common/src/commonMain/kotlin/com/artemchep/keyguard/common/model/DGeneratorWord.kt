package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort

data class DGeneratorWord(
    val id: String? = null,
    val word: String,
) : Comparable<DGeneratorWord> {
    override fun compareTo(other: DGeneratorWord): Int {
        return AlphabeticalSort.compareStr(word, other.word)
    }
}
