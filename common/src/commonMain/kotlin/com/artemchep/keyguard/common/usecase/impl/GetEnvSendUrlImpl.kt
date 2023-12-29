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
            val sendBaseUrl = token?.env?.back()?.buildSendUrl()
            requireNotNull(sendBaseUrl) { "Failed to get base send url." }

            val sendUrl = buildString {
                append(sendBaseUrl)
                append(send.accessId)
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
