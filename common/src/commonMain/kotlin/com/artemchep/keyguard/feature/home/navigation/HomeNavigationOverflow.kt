package com.artemchep.keyguard.feature.home.navigation

data class HomeNavigationOverflow<T>(
    val visible: List<T>,
    val overflow: List<T>,
)

fun <T> splitHomeNavigationItemsByMinSize(
    items: List<T>,
    availableSizePx: Int,
    itemMinSizePx: Int,
    overflowMinSizePx: Int = itemMinSizePx,
): HomeNavigationOverflow<T> {
    if (items.isEmpty()) {
        return HomeNavigationOverflow(
            visible = emptyList(),
            overflow = emptyList(),
        )
    }
    if (availableSizePx <= 0 || itemMinSizePx <= 0 || overflowMinSizePx <= 0) {
        return HomeNavigationOverflow(
            visible = emptyList(),
            overflow = items,
        )
    }

    val maxWithoutOverflow = availableSizePx / itemMinSizePx
    if (maxWithoutOverflow >= items.size) {
        return HomeNavigationOverflow(
            visible = items,
            overflow = emptyList(),
        )
    }

    val availableForItems = (availableSizePx - overflowMinSizePx)
        .coerceAtLeast(0)
    val maxWithOverflow = (availableForItems / itemMinSizePx)
        .coerceIn(0, items.lastIndex)
    return HomeNavigationOverflow(
        visible = items.take(maxWithOverflow),
        overflow = items.drop(maxWithOverflow),
    )
}
