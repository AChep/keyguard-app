package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.datetime.Instant

data class DGeneratorWordlist(
    val idRaw: Long,
    val name: String,
    val wordCount: Long,
    val createdDate: Instant,
) : Comparable<DGeneratorWordlist> {
    val id = idRaw.toString()

    val accentColor = run {
        val colors = generateAccentColors(name)
        colors
    }

    override fun compareTo(other: DGeneratorWordlist): Int {
        return name.compareTo(other.name, ignoreCase = true)
    }
}
