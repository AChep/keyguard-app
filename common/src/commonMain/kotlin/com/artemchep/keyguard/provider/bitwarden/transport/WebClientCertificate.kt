package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/**
 * The account's client certificate, for a web page of the account's
 * server that asks for one. Other hosts, such as the frames that
 * the page embeds, must not get it.
 */
class WebClientCertificate internal constructor(
    internal val mutualTls: ServerEnv.MutualTls,
    private val origins: Set<String>,
) {
    fun permits(host: String, port: Int): Boolean = mutualTlsOrigin(host, port) in origins

    // An imported certificate bundle includes the private key.
    override fun toString(): String = "WebClientCertificate(origins=$origins)"
}

fun ServerEnv.webClientCertificate(): WebClientCertificate? {
    val identity = mutualTls
        ?: return null
    return WebClientCertificate(
        mutualTls = identity,
        origins = mutualTlsOrigins(),
    )
}
