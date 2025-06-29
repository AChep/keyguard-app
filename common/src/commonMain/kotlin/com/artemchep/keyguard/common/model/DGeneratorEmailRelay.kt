package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.collections.immutable.ImmutableMap
import kotlin.time.Instant

data class DGeneratorEmailRelay(
    val id: String? = null,
    val name: String,
    val type: String,
    val data: ImmutableMap<String, String>,
    val createdDate: Instant,
) : Comparable<DGeneratorEmailRelay> {
    val accentColor = run {
        val colors = generateAccentColors(name)
        colors
    }

    override fun compareTo(other: DGeneratorEmailRelay): Int {
        return AlphabeticalSort.compareStr(name, other.name)
    }
}
