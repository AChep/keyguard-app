package com.artemchep.keyguard.apple

import com.artemchep.keyguard.PLACEHOLDER_URL_WEBDAV_COLLECTION
import com.artemchep.keyguard.PLACEHOLDER_URL_WEBDAV_KEEPASS_DATABASE
import com.artemchep.keyguard.URL_APPLE_STANDARD_EULA
import com.artemchep.keyguard.URL_GITHUB
import com.artemchep.keyguard.URL_MAC_APP_STORE_SUBSCRIPTIONS
import com.artemchep.keyguard.URL_PRIVACY_POLICY
import com.artemchep.keyguard.URL_REDDIT

/**
 * Re-exports the shared URL constants to Swift, which can't see
 * the top-level declarations of `:common`.
 */
object KeyguardUrls {
    const val GITHUB = URL_GITHUB
    const val REDDIT = URL_REDDIT
    const val PRIVACY_POLICY = URL_PRIVACY_POLICY
    const val APPLE_STANDARD_EULA = URL_APPLE_STANDARD_EULA
    const val MAC_APP_STORE_SUBSCRIPTIONS = URL_MAC_APP_STORE_SUBSCRIPTIONS

    const val PLACEHOLDER_WEBDAV_COLLECTION = PLACEHOLDER_URL_WEBDAV_COLLECTION
    const val PLACEHOLDER_WEBDAV_KEEPASS_DATABASE = PLACEHOLDER_URL_WEBDAV_KEEPASS_DATABASE
}
