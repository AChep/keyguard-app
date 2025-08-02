package com.artemchep.keyguard.common.model

object ShapeState {
    const val CENTER = 0
    const val START = 1
    const val END = 2
    const val ALL = START or END
}

fun <T> getShapeState(
    list: List<T>,
    index: Int,
    predicate: (T, Int) -> Boolean,
): Int {
    val topItem = list.getOrNull(index - 1)
        .let { it == null || !predicate(it, -1) }
    val bottomItem = list.getOrNull(index + 1)
        .let { it == null || !predicate(it, 1) }

    var flags = 0
    if (topItem) flags = flags or ShapeState.START
    if (bottomItem) flags = flags or ShapeState.END
    return flags
}
