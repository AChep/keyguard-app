package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.Url
import io.ktor.http.hostWithPort
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
// TODO: Add special treatments for androidapp:// etc.
class CipherUrlCheckImpl(
    private val tldService: TldService,
) : CipherUrlCheck {
    companion object {
        private const val PROTOCOL_ANDROID_APP = "androidapp://"
    }

    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
    )

    override fun invoke(
        uri: DSecret.Uri,
        url: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
    ): IO<Boolean> {
        return when (uri.match ?: defaultMatchDetection) {
            DSecret.Uri.MatchType.Domain -> {
                when {
                    uri.uri.startsWith(PROTOCOL_ANDROID_APP) -> ::checkUrlMatchByHost
                    else -> ::checkUrlMatchByDomain
                }
            }

            DSecret.Uri.MatchType.Host -> ::checkUrlMatchByHost
            DSecret.Uri.MatchType.StartsWith -> ::checkUrlMatchByStartsWith
            DSecret.Uri.MatchType.Exact -> ::checkUrlMatchByExact
            DSecret.Uri.MatchType.RegularExpression -> ::checkUrlMatchByRegularExpression
            DSecret.Uri.MatchType.Never -> ::checkUrlMatchByNever
        }.invoke(uri.uri, url)
    }

    private fun checkUrlMatchByDomain(
        a: String,
        b: String,
    ): IO<Boolean> = ioEffect {
        val aHost = urlOf(a).host
        val bHost = urlOf(b).host
        // Find the actual domain name from the host name. This
        // is quite trickery as there are quite a lot of very different
        // company owned names.
        val aDomain = tldService
            .getDomainName(aHost)
            .bind()
        bHost.endsWith(aDomain)
    }

    private fun checkUrlMatchByHost(
        a: String,
        b: String,
    ): IO<Boolean> = ioEffect {
        val aUrl = urlOf(a)
        val bUrl = urlOf(b)
        // If the url doesn't have a port specified, then
        // match it with any port.
        if (aUrl.specifiedPort == DEFAULT_PORT) {
            return@ioEffect aUrl.host == bUrl.host
        }

        aUrl.hostWithPort == bUrl.hostWithPort
    }

    private fun checkUrlMatchByStartsWith(
        a: String,
        b: String,
    ): IO<Boolean> = ioEffect {
        val aFiltered = a.trim().removeSuffix("/")
        val bFiltered = b.trim().removeSuffix("/")
        bFiltered.startsWith(aFiltered)
    }

    private fun checkUrlMatchByExact(
        a: String,
        b: String,
    ): IO<Boolean> = ioEffect {
        val aFiltered = a.trim().removeSuffix("/")
        val bFiltered = b.trim().removeSuffix("/")
        aFiltered == bFiltered
    }

    private fun checkUrlMatchByRegularExpression(
        a: String,
        b: String,
    ): IO<Boolean> = ioEffect {
        // URIs are mostly case-insensitive, so it makes sense
        // that regular expressions should also be case-insensitive.
        val aRegex = a.toRegex(RegexOption.IGNORE_CASE)
        b.matches(aRegex)
    }

    private fun checkUrlMatchByNever(
        a: String,
        b: String,
    ): IO<Boolean> = io(false)

    private fun urlOf(url: String): Url {
        val newUrl = ensureUrlSchema(url)
        return Url(newUrl)
    }

    private fun ensureUrlSchema(url: String) =
        if (url.isBlank() || url.contains("://")) {
            // The url contains a schema, return
            // it as it as.
            url
        } else {
            val newUrl = "https://$url"
            newUrl
        }
}
