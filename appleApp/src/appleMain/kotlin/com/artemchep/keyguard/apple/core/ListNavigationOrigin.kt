package com.artemchep.keyguard.apple.core

/** The list whose selection owns a detail branch; null [listEntryId] means the section root. */
data class ListNavigationOrigin(
    val scope: String,
    val listEntryId: Long?,
)

/** A missing anchor must never turn a delayed row-open into navigation in another list. */
internal fun listDetailStartIndex(entryIds: List<Long>, listEntryId: Long?): Int? =
    if (listEntryId == null) {
        0
    } else {
        entryIds.indexOf(listEntryId).takeIf { it >= 0 }?.plus(1)
    }
