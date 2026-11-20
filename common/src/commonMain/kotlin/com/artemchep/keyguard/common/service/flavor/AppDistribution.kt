package com.artemchep.keyguard.common.service.flavor

data class AppDistribution(
    /**
     * `true` if the app came from an app store. A store
     * delivers the updates itself, so the app must not link
     * to the GitHub releases.
     */
    val isStore: Boolean,
    /**
     * The listing of the app in its store, or `null` if the
     * listing is unknown. Read only on store builds.
     */
    val storeUrl: String? = null,
)
