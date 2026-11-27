package com.artemchep.keyguard.feature.secretscan

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.normalizeSshFingerprint
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.common.service.secretscan.secretScanLiteralDigest
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.coerceEnvironmentName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val DOTENV_FILE_NAME = ".env"

/** The last segment of a path, with either separator. */
internal fun secretScanFileName(path: String): String = path
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')

private fun secretScanParentName(path: String): String? {
    val trimmed = path.trimEnd('/', '\\')
    val index = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    if (index <= 0) return null
    return secretScanFileName(trimmed.substring(0, index))
        .takeIf { it.isNotBlank() }
}

/**
 * The name of the vault item of a `.env` file: its parent folder name, e.g.
 * `my-api`, plus ` (.env.production)` for a file that is not a plain `.env`.
 * Cut to the longest name an environment takes; the file name if blank.
 */
internal fun secretScanDotEnvName(path: String): String {
    val fileName = secretScanFileName(path)
    val parentName = secretScanParentName(path)
    val name = when {
        parentName == null -> fileName
        fileName == DOTENV_FILE_NAME -> parentName
        else -> "$parentName ($fileName)"
    }
    return coerceEnvironmentName(name)
        .ifBlank { coerceEnvironmentName(fileName) }
}

/**
 * The keys of what the vault already holds of the [kind], to mark the
 * findings that are in it: the fingerprints of the SSH keys, or the
 * [secretScanLiteralDigest]s of the environments.
 */
internal fun Flow<List<DSecret>>.secretScanVaultIndex(
    kind: SecretScanKind,
    cryptoGenerator: CryptoGenerator,
): Flow<Set<String>> {
    val live = map { secrets ->
        secrets.filter { it.deletedDate == null }
    }
    val index = when (kind) {
        SecretScanKind.SshKey -> live
            .map { secrets ->
                secrets.mapNotNullTo(mutableSetOf()) { secret ->
                    secret.sshKey?.fingerprint?.normalizeSshFingerprint()
                }
            }

        // Hashed only when the environments of the vault change.
        SecretScanKind.DotEnv -> live
            .map { secrets ->
                secrets.mapNotNull { secret ->
                    (secret.environment as? EnvironmentPayload.Decoded)?.definition
                }
            }
            .distinctUntilChanged()
            .map { environments ->
                environments.mapNotNullTo(mutableSetOf()) {
                    it.secretScanLiteralDigest(cryptoGenerator)
                }
            }
    }
    return index.distinctUntilChanged()
}
