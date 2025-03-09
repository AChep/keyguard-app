package com.artemchep.keyguard.common.model

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.datetime.Instant

data class DCipherFilter(
    val idRaw: Long,
    val icon: ImageVector?,
    val name: String,
    val filter: Map<String, Set<DFilter.Primitive>>,
    val updatedDate: Instant,
    val createdDate: Instant,
) : Comparable<DCipherFilter> {
    val id = idRaw.toString()

    val accentColor = run {
        val colors = generateAccentColors(name)
        colors
    }

    override fun compareTo(other: DCipherFilter): Int {
        return AlphabeticalSort.compareStr(name, other.name)
    }
}
