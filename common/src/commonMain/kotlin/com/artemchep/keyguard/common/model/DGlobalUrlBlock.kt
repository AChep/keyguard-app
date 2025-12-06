package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.time.Instant

data class DGlobalUrlBlock(
    val id: String? = null,
    val name: String,
    val description: String,
    val uri: String,
    val mode: MatchDetection,
    val createdDate: Instant,
    val enabled: Boolean,
    val exposed: Boolean,
) : Comparable<DGlobalUrlBlock> {
    val accentColor = run {
        val colors = generateAccentColors(uri)
        colors
    }

    override fun compareTo(other: DGlobalUrlBlock): Int {
        return AlphabeticalSort.compareStr(uri, other.uri)
    }
}
