package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.time.Instant

data class DGlobalUrlOverride(
    val id: String? = null,
    val name: String,
    val regex: Regex,
    val command: String,
    val createdDate: Instant,
    val enabled: Boolean,
) : Comparable<DGlobalUrlOverride> {
    val accentColor = run {
        val colors = generateAccentColors(name)
        colors
    }

    override fun compareTo(other: DGlobalUrlOverride): Int {
        return AlphabeticalSort.compareStr(name, other.name)
    }
}
