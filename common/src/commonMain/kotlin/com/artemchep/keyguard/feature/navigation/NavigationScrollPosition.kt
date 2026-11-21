package com.artemchep.keyguard.feature.navigation

/**
 * Saved scroll position of a list screen, owned by its [NavigationEntry].
 *
 * @property itemKey stable key of the first/anchor visible item, used to follow
 *   the same item if items are added or removed above it
 * @property itemIndex fallback index used when [itemKey] is no longer present
 * @property itemOffset pixel scroll offset of that item (may be negative on Wear)
 * @property toolbarHeightOffsetLimit saved top app bar height offset limit
 * @property toolbarHeightOffset saved top app bar height offset
 * @property toolbarContentOffset saved top app bar content offset
 */
data class NavigationScrollPosition(
    val itemKey: String? = null,
    val itemIndex: Int = 0,
    val itemOffset: Int = 0,
    val toolbarHeightOffsetLimit: Float = -Float.MAX_VALUE,
    val toolbarHeightOffset: Float = 0f,
    val toolbarContentOffset: Float = 0f,
)

/**
 * Resolved `(index, offset)` target for restoring a [NavigationScrollPosition]
 * against a non-empty list of item keys.
 */
data class ResolvedScrollTarget(
    val index: Int,
    val offset: Int,
)

/**
 * Resolves this saved scroll position against the current [itemKeys].
 * Returns `null` when [itemKeys] is empty (e.g. while loading or empty).
 *
 * When [itemKey] is still present in [itemKeys], keeps its exact [itemOffset];
 * otherwise clamps [itemIndex] into `0..itemKeys.lastIndex` and resets offset
 * to `0` so a different item's pixel offset is not applied to an unrelated row.
 */
fun NavigationScrollPosition.resolve(
    itemKeys: List<String>,
): ResolvedScrollTarget? = if (itemKeys.isEmpty()) {
    null
} else {
    val keyIndex = itemKey
        ?.let { itemKeys.indexOf(it) }
        ?.takeIf { it >= 0 }
    if (keyIndex != null) {
        ResolvedScrollTarget(
            index = keyIndex,
            offset = itemOffset,
        )
    } else {
        ResolvedScrollTarget(
            index = itemIndex.coerceIn(0, itemKeys.lastIndex),
            offset = 0,
        )
    }
}
