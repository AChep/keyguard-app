package com.artemchep.keyguard.common.model

/**
 * A newer release of the app the user should know about.
 */
data class DAppUpdate(
    /** The version name of the newest release, e.g. `3.8.2`. */
    val version: String,
    /**
     * The release page, or the store listing on store builds: the store
     * delivers the update. `null` if there is nothing the app can open:
     * a store build without a known listing, or a platform without
     * a browser.
     */
    val url: String?,
    /**
     * The git tag of the newest release that is public for at least the
     * delay. The app notifies once per such release, so a release that
     * is still inside the delay does not cause a notification.
     */
    val eligibleTag: String,
)
