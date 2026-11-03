package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/**
 * The account's client certificate, for a web page of the account's
 * server that asks for one. Other hosts, such as the frames that
 * the page embeds, must not get it.
 */
class WebClientCertificate internal constructor(
    internal val pkcs12Base64: String,
    private val origins: Set<String>,
) {
    fun permits(host: String, port: Int): Boolean = mutualTlsOrigin(host, port) in origins

    // The certificate bundle includes the private key.
    override fun toString(): String = "WebClientCertificate(origins=$origins)"
}

fun ServerEnv.webClientCertificate(): WebClientCertificate? {
    val identity = mutualTls as? ServerEnv.MutualTls.ImportedPkcs12
        ?: return null
    return WebClientCertificate(
        pkcs12Base64 = identity.pkcs12Base64,
        origins = mutualTlsOrigins(),
    )
}
