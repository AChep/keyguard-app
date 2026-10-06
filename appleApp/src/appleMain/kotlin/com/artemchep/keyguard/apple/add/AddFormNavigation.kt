package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.feature.navigation.NavigationIntent

/** A successful save removes this form, sometimes alongside opening its result. */
internal fun NavigationIntent.closesAddForm(screenId: String): Boolean = when (this) {
    is NavigationIntent.PopById -> id == screenId && !exclusive
    is NavigationIntent.Composite -> list.any { it.closesAddForm(screenId) }
    else -> false
}
