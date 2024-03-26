package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.url
import com.artemchep.keyguard.common.usecase.GetEnvSendUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildSendUrl
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetEnvSendUrlImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val base64Service: Base64Service,
) : GetEnvSendUrl {
    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun invoke(
        send: DSend,
    ): IO<String> = tokenRepository
        .getById(AccountId(send.accountId))
        .effectMap { token ->
            val baseUrl = token?.env?.back()?.buildSendUrl()
            requireNotNull(baseUrl) { "Failed to get base send url." }

            val accessId = send.accessId.takeIf { it.isNotEmpty() }
            requireNotNull(accessId) { "Failed to get access id." }

            val sendUrl = buildString {
                append(baseUrl)
                append(accessId)
                append('/')

                val key = send.keyBase64
                    ?.let(base64Service::url)
                if (key != null) {
                    append(key)
                }
            }
            sendUrl
        }
}
