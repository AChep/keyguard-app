package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend

sealed interface SendUpdate {
    companion object;

    val source: BitwardenSend

    data class Modify(
        override val source: BitwardenSend,
        val sendId: String,
        val sendRequest: SendRequest,
    ) : SendUpdate

    data class Create(
        override val source: BitwardenSend,
        val sendRequest: SendRequest,
    ) : SendUpdate
}

context(CryptoGenerator, Base64Service)
fun SendUpdate.Companion.of(
    model: BitwardenSend,
    key: ByteArray,
) = when {
    model.service.remote != null -> {
        SendUpdate.Modify(
            source = model,
            sendId = model.service.remote.id,
            sendRequest = SendRequest.of(
                model = model,
                key = key,
            ),
        )
    }

    else -> {
        SendUpdate.Create(
            source = model,
            sendRequest = SendRequest.of(
                model = model,
                key = key,
            ),
        )
    }
}
