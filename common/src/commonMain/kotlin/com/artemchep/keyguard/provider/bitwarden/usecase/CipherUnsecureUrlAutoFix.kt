package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.optics.dsl.notNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlAutoFix
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.core.store.bitwarden.uris
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherUnsecureUrlAutoFixImpl(
    private val httpClient: HttpClient,
    private val modifyCipherById: ModifyCipherById,
) : CipherUnsecureUrlAutoFix {
    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToUrls: Map<String, Set<String>>,
    ): IO<Unit> = modifyCipherById(
        cipherIdsToUrls
            .keys,
    ) { model ->
        val urisToFix = cipherIdsToUrls[model.cipherId].orEmpty()
        val uris = model.data_.login
            ?.uris
            ?.map { uri ->
                val shouldAutoFix = uri.uri in urisToFix
                if (shouldAutoFix && uri.uri != null) {
                    val secureUrl = autofyUrl(uri.uri)
                    uri.copy(uri = secureUrl)
                } else {
                    uri
                }
            }
            .orEmpty()

        var new = model
        new = new.copy(
            data_ = BitwardenCipher.login.notNull.uris.set(new.data_, uris),
        )
        new
    }.map { Unit }

    private suspend fun autofyUrl(unsecureUrl: String): String {
        val url = Url(unsecureUrl)
        if (url.protocol.isSecure()) {
            throw IllegalStateException("The '$unsecureUrl' uses a secure protocol.")
        }
        val secureProtocol =
            CipherUnsecureUrlCheckUtils.unsecureToSecureProtocols[url.protocol.name]
            // Should not happen, as we should not allow
            // user to auto-fix unknown protocols.
                ?: throw IllegalStateException("Protocol '${url.protocol.name}' doesn't have a secure variant.")
        val secureUrl = URLBuilder(url).apply {
            protocol = secureProtocol
            // unless specified port is different to the previous default
            port = url.port.takeIf { url.port != url.protocol.defaultPort }
                ?: DEFAULT_PORT
        }.build()

        // Test if the new url is GET-able
        testUrlOrThrow(secureUrl)
        return secureUrl.toString()
    }

    private suspend fun testUrlOrThrow(url: Url) {
        val secureUrlExists = httpClient.get(url).status.isSuccess()
        if (!secureUrlExists) {
            throw IllegalStateException("Could not check if secure URL exists.")
        }
    }
}
