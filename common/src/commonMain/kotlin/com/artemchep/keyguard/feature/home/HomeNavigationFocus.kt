package com.artemchep.keyguard.feature.home

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Returns `true` while the home navigation rail or bar holds focus. A
 * screen that focuses something when it opens leaves the focus there,
 * so the keyboard keeps switching tabs.
 */
internal val LocalHomeNavigationFocused = staticCompositionLocalOf<() -> Boolean> {
    { false }
}
