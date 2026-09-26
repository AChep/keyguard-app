package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.core.getOrElse
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl

/**
 * @author Artem Chepurnyi
 */
class CipherUnsecureUrlCheckImpl : CipherUnsecureUrlCheck {
    private val regex = kotlin.run {
        val protocols = CipherUnsecureUrlCheckUtils.unsecureProtocols
        val protocolsGroup = "(" + protocols.joinToString(separator = "|") { it.name } + ")"
        "\\s*$protocolsGroup://.*".toRegex()
    }

    override fun invoke(url: String): Boolean =
        isUnsecureUrl(url) &&
                // Most of us do not care about the https for
                // local web sites.
                !isLocalUrl(url)

    private fun isUnsecureUrl(url: String) = regex.matches(url)

    // TODO: Allow reserved IP addresses to be unsafe
    //  https://en.wikipedia.org/wiki/Reserved_IP_addresses#IPv4
    private fun isLocalUrl(url: String) = verifyIsLocalUrl(url).getOrElse { false }
}
