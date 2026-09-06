package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata

fun generatedGpgKey(
    privateKey: String = "PRIVATE",
    publicKey: String = "PUBLIC",
    fingerprint: String = "FINGERPRINT",
    metadata: GpgAgentKeyMetadata? = GpgAgentKeyMetadata(),
    userId: String = "Keyguard Test <test@example.com>",
    typeLabel: String = "OpenPGP",
): GeneratedGpgKey = GeneratedGpgKey(
    privateKeyArmored = privateKey,
    publicKeyArmored = publicKey,
    fingerprint = fingerprint,
    metadata = metadata,
    userId = userId,
    typeLabel = typeLabel,
)
