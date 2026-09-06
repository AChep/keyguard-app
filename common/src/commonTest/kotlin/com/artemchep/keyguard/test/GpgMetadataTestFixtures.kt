package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCertificateMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentRole
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation

/**
 * The canonical single-certificate metadata used across tests: one EDDSA
 * primary component with stored secret material and signing capability.
 */
fun gpgCanonicalMetadata(
    fingerprint: String,
    keygrip: String,
): GpgAgentKeyMetadata = GpgAgentKeyMetadata(
    certificates = listOf(
        GpgAgentCertificateMetadata(
            primaryFingerprint = fingerprint,
            components = listOf(
                GpgAgentKeyComponentMetadata(
                    fingerprint = fingerprint,
                    role = GpgAgentKeyComponentRole.PRIMARY,
                    publicKeyAlgorithmId = 22,
                    algorithm = "EDDSA",
                    keygrips = listOf(keygrip),
                    storedSecretMaterial = true,
                    agentOperations = setOf(GpgAgentOperation.SIGN),
                ),
            ),
        ),
    ),
)

fun gpgMetadata(
    vararg keys: GpgAgentKeyMetadataKey,
    storedSecretMaterial: Boolean = true,
): GpgAgentKeyMetadata = GpgAgentKeyMetadata(
    certificates = keys
        .firstOrNull()
        ?.let { primary ->
            listOf(
                GpgAgentCertificateMetadata(
                    primaryFingerprint = primary.fingerprint,
                    components = keys.mapIndexed { index, key ->
                        GpgAgentKeyComponentMetadata(
                            fingerprint = key.fingerprint,
                            role = if (index == 0) {
                                GpgAgentKeyComponentRole.PRIMARY
                            } else {
                                GpgAgentKeyComponentRole.SUBKEY
                            },
                            publicKeyAlgorithmId = 0,
                            algorithm = key.algorithm,
                            keygrips = listOf(key.keygrip),
                            storedSecretMaterial = storedSecretMaterial,
                            agentOperations = buildSet {
                                if (key.canSign) add(GpgAgentOperation.SIGN)
                                if (key.canDecrypt) add(GpgAgentOperation.DECRYPT)
                            },
                        )
                    },
                ),
            )
        }
        .orEmpty(),
)
