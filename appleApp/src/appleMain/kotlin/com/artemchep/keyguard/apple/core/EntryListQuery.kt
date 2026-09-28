package com.artemchep.keyguard.apple.core

/** Navigation owns the input; only programmatic replacements advance its revision. */
internal data class EntryListQuery(
    val text: String = "",
    val textRevision: Int = 0,
)
