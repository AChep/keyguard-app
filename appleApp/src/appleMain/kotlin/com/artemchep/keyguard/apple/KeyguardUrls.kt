package com.artemchep.keyguard.apple

import com.artemchep.keyguard.PLACEHOLDER_S3_BUCKET as SHARED_PLACEHOLDER_S3_BUCKET
import com.artemchep.keyguard.PLACEHOLDER_S3_KEEPASS_KEY as SHARED_PLACEHOLDER_S3_KEEPASS_KEY
import com.artemchep.keyguard.PLACEHOLDER_S3_PREFIX as SHARED_PLACEHOLDER_S3_PREFIX
import com.artemchep.keyguard.PLACEHOLDER_S3_REGION as SHARED_PLACEHOLDER_S3_REGION
import com.artemchep.keyguard.PLACEHOLDER_URL_S3_ENDPOINT
import com.artemchep.keyguard.PLACEHOLDER_URL_WEBDAV_COLLECTION
import com.artemchep.keyguard.PLACEHOLDER_URL_WEBDAV_KEEPASS_DATABASE
import com.artemchep.keyguard.URL_2FA
import com.artemchep.keyguard.URL_APPLE_STANDARD_EULA
import com.artemchep.keyguard.URL_GITHUB
import com.artemchep.keyguard.URL_HAVE_I_BEEN_PWNED
import com.artemchep.keyguard.URL_MAC_APP_STORE_SUBSCRIPTIONS
import com.artemchep.keyguard.URL_PASSKEYS
import com.artemchep.keyguard.URL_PRIVACY_POLICY
import com.artemchep.keyguard.URL_REDDIT

/** Re-exports the shared URL constants to Swift, which can't see the top-level declarations of `:common`. */
object KeyguardUrls {
    const val GITHUB = URL_GITHUB
    const val REDDIT = URL_REDDIT
    const val PRIVACY_POLICY = URL_PRIVACY_POLICY
    const val APPLE_STANDARD_EULA = URL_APPLE_STANDARD_EULA
    const val MAC_APP_STORE_SUBSCRIPTIONS = URL_MAC_APP_STORE_SUBSCRIPTIONS
    const val HAVE_I_BEEN_PWNED = URL_HAVE_I_BEEN_PWNED
    const val TWO_FA_DIRECTORY = URL_2FA
    const val PASSKEYS_DIRECTORY = URL_PASSKEYS

    const val PLACEHOLDER_WEBDAV_COLLECTION = PLACEHOLDER_URL_WEBDAV_COLLECTION
    const val PLACEHOLDER_WEBDAV_KEEPASS_DATABASE = PLACEHOLDER_URL_WEBDAV_KEEPASS_DATABASE
    const val PLACEHOLDER_S3_ENDPOINT = PLACEHOLDER_URL_S3_ENDPOINT
    const val PLACEHOLDER_S3_REGION = SHARED_PLACEHOLDER_S3_REGION
    const val PLACEHOLDER_S3_BUCKET = SHARED_PLACEHOLDER_S3_BUCKET
    const val PLACEHOLDER_S3_PREFIX = SHARED_PLACEHOLDER_S3_PREFIX
    const val PLACEHOLDER_S3_KEEPASS_KEY = SHARED_PLACEHOLDER_S3_KEEPASS_KEY
}
