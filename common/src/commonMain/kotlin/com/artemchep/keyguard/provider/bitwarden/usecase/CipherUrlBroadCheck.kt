package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretBroadUrlGroup
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.PROTOCOL_IOS_APP
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherUrlBroadCheckImpl(
    private val tldService: TldService,
) : CipherUrlBroadCheck {
    companion object {
        private const val TAG = "CipherUrlBroadCheck"
    }

    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
    )

    override fun invoke(
        ciphers: List<DSecret>,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomainsBuilder: EquivalentDomainsBuilder,
    ): IO<List<DSecretBroadUrlGroup>> = ioEffect {
        // Collect all of the urls with the host
        // detection. The idea is that if we have a URL
        // that uses host detection, then the base domain URL
        // is likely too broad.
        val domainsWithHostDetection = mutableSetOf<String>()
        ciphers.forEach { cipher ->
            cipher.uris.forEach uriForEach@{ uri ->
                val matchDetection = uri.match
                    ?: defaultMatchDetection
                if (matchDetection != DSecret.Uri.MatchType.Host) {
                    return@uriForEach
                }
                if (
                    uri.uri.startsWith(PROTOCOL_ANDROID_APP) ||
                    uri.uri.startsWith(PROTOCOL_IOS_APP)
                ) {
                    return@uriForEach
                }

                // Extract top level domain from the
                // given URI.
                val domain = tldService
                    .getDomainName(uri.uri)
                    .attempt()
                    .bind()
                    .getOrNull()
                    ?: return@uriForEach
                domainsWithHostDetection += domain
            }
        }
        return@ioEffect ciphers
            .flatMap cipherFlatMap@{ cipher ->
                cipher
                    .uris
                    .mapNotNull uriMap@{ uri ->
                        val matchDetection = uri.match
                            ?: defaultMatchDetection
                        if (matchDetection != DSecret.Uri.MatchType.Domain) {
                            return@uriMap null
                        }
                        if (
                            uri.uri.startsWith(PROTOCOL_ANDROID_APP) ||
                            uri.uri.startsWith(PROTOCOL_IOS_APP)
                        ) {
                            return@uriMap null
                        }

                        // Extract top level domain from the
                        // given URI.
                        val domain = tldService
                            .getDomainName(uri.uri)
                            .attempt()
                            .bind()
                            .getOrNull()
                            ?: return@uriMap null
                        val domainEq = kotlin.run {
                            val equivalentDomains = equivalentDomainsBuilder
                                .getAndCache(cipher.accountId)
                            equivalentDomains.findEqDomains(domain)
                        }
                        val match = domainEq.any { d -> d in domainsWithHostDetection }
                        if (!match) {
                            return@uriMap null
                        }

                        val value = domain + "|" + uri.uri
                        DSecretBroadUrlGroup(
                            value = value,
                            cipher = cipher,
                        )
                    }
            }
    }
}
