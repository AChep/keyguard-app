package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import io.ktor.http.Url

/**
 * Provides info about the platform of the resource that
 * the URI doe refer.
 *
 * @author Artem Chepurnyi
 */
sealed interface LinkInfoPlatform : LinkInfo {
    data class Android(
        val packageName: String,
    ) : LinkInfoPlatform {
        /**
         * A url to the app's content page on the Play Store formed by appending
         * the package name to a base Play Store url.
         */
        val playStoreUrl = "https://play.google.com/store/apps/details?id=$packageName"

        /**
         * A url to the app's content page on the F-Droid.
         */
        val fDroidUrl = "https://f-droid.org/en/packages/$packageName"

        val uri get() = PROTOCOL_ANDROID_APP + packageName
    }

    data class IOS(
        val bundleId: String,
    ) : LinkInfoPlatform

    data class Web(
        val url: Url,
        val frontPageUrl: Url,
    ) : LinkInfoPlatform

    data object Other : LinkInfoPlatform
}
