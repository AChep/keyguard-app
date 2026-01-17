package com.artemchep.keyguard.common.service.extract.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.PROTOCOL_IOS_APP
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlin.reflect.KClass

class LinkInfoPlatformExtractor : LinkInfoExtractor<DSecret.Uri, LinkInfoPlatform> {
    companion object {
        private const val HTTP_SCHEME_PREFIX = "http://"
        private const val HTTPS_SCHEME_PREFIX = "https://"
    }

    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoPlatform> get() = LinkInfoPlatform::class

    override fun extractInfo(uri: DSecret.Uri): IO<LinkInfoPlatform> = ioEffect {
        val url = uri.uri
        when {
            url.startsWith(PROTOCOL_ANDROID_APP, ignoreCase = true) ->
                createAndroidPlatform(uri)

            url.startsWith(PROTOCOL_IOS_APP, ignoreCase = true) ->
                createIOSPlatform(uri)

            url.startsWith(HTTP_SCHEME_PREFIX, ignoreCase = true) ||
                    url.startsWith(HTTPS_SCHEME_PREFIX, ignoreCase = true) ->
                createWebPlatform(uri)

            else -> LinkInfoPlatform.Other
        }
    }

    private fun createAndroidPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(PROTOCOL_ANDROID_APP.length)
        return LinkInfoPlatform.Android(
            packageName = packageName,
        )
    }

    private fun createIOSPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(PROTOCOL_IOS_APP.length)
        return LinkInfoPlatform.IOS(
            bundleId = packageName,
        )
    }

    private fun createWebPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val parsedUri = Either
            .catch {
                Url(uri.uri)
            }
            .getOrNull()
        // failed to parse the url, probably we can not open it
            ?: return LinkInfoPlatform.Other

        val frontPageUrl = URLBuilder(parsedUri).apply {
            parameters.clear()
            fragment = ""
            encodedPath = ""
        }.build()
        return LinkInfoPlatform.Web(
            url = parsedUri,
            frontPageUrl = frontPageUrl,
        )
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}
