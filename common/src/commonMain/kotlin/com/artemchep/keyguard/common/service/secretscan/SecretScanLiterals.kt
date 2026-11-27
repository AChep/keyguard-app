package com.artemchep.keyguard.common.service.secretscan

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable

/**
 * The value of the variable if it counts as a plaintext secret: a non-empty
 * literal. `null` otherwise.
 */
fun EnvironmentVariable.secretScanLiteralOrNull(): String? =
    (source as? EnvironmentValueSource.Literal)
        ?.value
        ?.takeIf { it.isNotEmpty() }

/**
 * A digest of the variables with a non-empty literal value, by name. Two
 * environments with the same digest hold the same name→literal set. `null`
 * if there is no such variable.
 */
fun EnvironmentDefinition.secretScanLiteralDigest(
    cryptoGenerator: CryptoGenerator,
): String? {
    val literals = variables
        .mapNotNull { variable ->
            val value = variable.secretScanLiteralOrNull()
                ?: return@mapNotNull null
            variable.name to value
        }
        // A later definition of a name wins, as in a shell.
        .toMap()
        .entries
        .sortedBy { it.key }
    if (literals.isEmpty()) return null
    val text = buildString {
        literals.forEach { (name, value) ->
            append(name.length).append(':').append(name)
            append(value.length).append(':').append(value)
        }
    }
    return cryptoGenerator.hashSha256(text.encodeToByteArray()).toHex()
}
