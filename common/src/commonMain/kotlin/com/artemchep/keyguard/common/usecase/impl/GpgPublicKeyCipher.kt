package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPublicKeyArmored
import com.artemchep.keyguard.common.usecase.GetCiphers
import kotlinx.coroutines.flow.first

internal suspend fun GetCiphers.requireGpgPublicKeyCipher(
    cipherId: String,
    accountId: String,
): Pair<DSecret, String> {
    val cipher = invoke()
        .first()
        .firstOrNull { cipher ->
            cipher.id == cipherId && cipher.accountId == accountId
        }
        ?: throw IllegalStateException("The GPG key item was not found.")

    val publicKeyArmored = cipher.getGpgAgentPublicKeyArmored()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("The item does not contain a public GPG key.")

    return cipher to publicKeyArmored
}
