package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

sealed interface CipherUpdate {
    companion object;

    val source: BitwardenCipher

    data class Modify(
        override val source: BitwardenCipher,
        val cipherId: String,
        val cipherRequest: CipherRequest,
    ) : CipherUpdate

    data class Create(
        override val source: BitwardenCipher,
        val cipherRequest: CipherRequest,
    ) : CipherUpdate

    data class CreateInOrg(
        override val source: BitwardenCipher,
        val cipherRequest: CipherCreateRequest,
    ) : CipherUpdate
}

fun CipherUpdate.Companion.of(
    model: BitwardenCipher,
    folders: Map<String, String?>,
) = when {
    model.service.remote != null -> {
        CipherUpdate.Modify(
            source = model,
            cipherId = model.service.remote.id,
            cipherRequest = CipherRequest.of(
                model = model,
                folders = folders,
            ),
        )
    }
    // Remote ID does not exist, therefore the items has not yet
    // been created on remote.
    model.organizationId != null -> {
        CipherUpdate.CreateInOrg(
            source = model,
            cipherRequest = CipherCreateRequest.of(
                model = model,
                folders = folders,
            ),
        )
    }

    else -> {
        CipherUpdate.Create(
            source = model,
            cipherRequest = CipherRequest.of(
                model = model,
                folders = folders,
            ),
        )
    }
}
