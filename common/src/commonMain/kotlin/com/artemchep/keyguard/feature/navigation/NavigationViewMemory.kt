package com.artemchep.keyguard.feature.navigation

/**
 * What the content of a [NavigationEntry] remembers about its view, so
 * the content can show it again when it comes back, such as when a screen
 * on top of it closes. It lives as long as the entry. Main thread only.
 */
class NavigationViewMemory {
    /** The key of the target focused last. */
    var lastFocusedKey: String? = null

    /**
     * Like [lastFocusedKey], but only for the rows of a list, not the
     * targets next to it, such as its toolbar buttons.
     */
    var lastFocusedRowKey: String? = null

    /** The scroll position of the list. */
    var scrollPosition: NavigationScrollPosition? = null
}
