package com.artemchep.keyguard.common.service.extract.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlin.reflect.KClass

class LinkInfoPlatformExtractor : LinkInfoExtractor<DSecret.Uri, LinkInfoPlatform> {
    companion object {
        private const val ANDROID_SCHEME_PREFIX = "androidapp://"
        private const val IOS_SCHEME_PREFIX = "iosapp://"

        private const val HTTP_SCHEME_PREFIX = "http://"
        private const val HTTPS_SCHEME_PREFIX = "https://"
    }

    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoPlatform> get() = LinkInfoPlatform::class

    override fun extractInfo(uri: DSecret.Uri): IO<LinkInfoPlatform> = ioEffect {
        val url = uri.uri
        when {
            url.startsWith(ANDROID_SCHEME_PREFIX, ignoreCase = true) ->
                createAndroidPlatform(uri)

            url.startsWith(IOS_SCHEME_PREFIX, ignoreCase = true) ->
                createIOSPlatform(uri)

            url.startsWith(HTTP_SCHEME_PREFIX, ignoreCase = true) ||
                    url.startsWith(HTTPS_SCHEME_PREFIX, ignoreCase = true) ->
                createWebPlatform(uri)

            else -> LinkInfoPlatform.Other
        }
    }

    private fun createAndroidPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(ANDROID_SCHEME_PREFIX.length)
        return LinkInfoPlatform.Android(
            packageName = packageName,
        )
    }

    private fun createIOSPlatform(uri: DSecret.Uri): LinkInfoPlatform {
        val packageName = uri.uri
            .substring(IOS_SCHEME_PREFIX.length)
        return LinkInfoPlatform.IOS(
            packageName = packageName,
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
