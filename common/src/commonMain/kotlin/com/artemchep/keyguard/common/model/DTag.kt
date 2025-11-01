package com.artemchep.keyguard.common.model

data class DTag(
    val name: String,
) : Comparable<DTag> {
    private val comparator = compareBy<DTag>(
        { name },
    )

    override fun compareTo(other: DTag): Int = comparator.compare(this, other)
}
