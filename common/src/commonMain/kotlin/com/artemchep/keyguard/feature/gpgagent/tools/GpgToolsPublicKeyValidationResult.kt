package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.isActiveAt
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.crypto.eraseAll
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_public_key_invalid
import com.artemchep.keyguard.res.gpg_tools_public_key_not_encryptable
import com.artemchep.keyguard.res.gpg_tools_public_key_private
import org.jetbrains.compose.resources.StringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface GpgToolsPublicKeyValidationResult {
    data class Success(val keys: List<GpgPublicKeyInfo>) : GpgToolsPublicKeyValidationResult
    data class Error(val resource: StringResource) : GpgToolsPublicKeyValidationResult
}

private const val PUBLIC_KEY_ARMOR_BEGIN = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
private const val PUBLIC_KEY_ARMOR_END = "-----END PGP PUBLIC KEY BLOCK-----"
private val PRIVATE_KEY_ARMOR_LABELS = listOf("-----BEGIN PGP PRIVATE KEY", "-----BEGIN PGP SECRET KEY")

/** Validates temporary public certificates without retaining the pasted packet text. */
@Suppress("ReturnCount")
fun validateGpgToolsPublicKeys(
    text: String,
    parser: GpgPublicKeyParser,
    forEncryption: Boolean,
): GpgToolsPublicKeyValidationResult {
    fun invalid() = GpgToolsPublicKeyValidationResult.Error(Res.string.gpg_tools_public_key_invalid)
    if (PRIVATE_KEY_ARMOR_LABELS.any { text.contains(it, ignoreCase = true) }) {
        return GpgToolsPublicKeyValidationResult.Error(Res.string.gpg_tools_public_key_private)
    }
    val parsed = runCatchingNonFatal { parser.parse(text) }.getOrNull()
        as? GpgPublicKeyParseResult.Success ?: return invalid()
    if (parsed.keys.isEmpty() || parsed.skippedCertificates != 0) return invalid()

    // Public parsing preserves each original certificate's packet span, whereas
    // secret parsing returns a public projection. Compare decoded bytes before
    // deduplication to reject disguised secret input and silently skipped data.
    // This delegates packet interpretation entirely to the existing parser.
    val input = decodePublicCertificateArmor(text) ?: return invalid()
    val expected = decodePublicCertificateArmor(parsed.keys.joinToString("\n") { it.publicKeyArmored })
    try {
        if (expected == null || !expected.contentEquals(input)) return invalid()
    } finally {
        listOfNotNull(input, expected).eraseAll()
    }

    val keys = parsed.keys.map { it.copy(fingerprint = it.fingerprint.normalizeGpgFingerprint()) }
    if (keys.any { it.fingerprint.isEmpty() }) return invalid()
    if (forEncryption) {
        val now = Clock.System.now()
        if (keys.any { !it.canEncryptAuthenticatedAt(now) }) {
            return GpgToolsPublicKeyValidationResult.Error(Res.string.gpg_tools_public_key_not_encryptable)
        }
    }
    return GpgToolsPublicKeyValidationResult.Success(keys.distinctBy { it.fingerprint })
}

/** Like `canEncryptAt`, but only policy-authenticated components count. */
private fun GpgPublicKeyInfo.canEncryptAuthenticatedAt(now: Instant): Boolean =
    authenticated && isActiveAt(now) &&
            (canEncrypt || subKeys.any { it.authenticated && it.canEncrypt && it.isActiveAt(now) })

/**
 * Decodes one or more concatenated public-key armor blocks into their packet bytes.
 * Armor framing only; certificate and signature validation belong to the parser.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun decodePublicCertificateArmor(text: String): ByteArray? = runCatchingNonFatal {
    val lines = text.trim().lines().map(String::trim)
    val decoded = mutableListOf<ByteArray>()
    var index = 0
    while (index < lines.size) {
        if (lines[index].isEmpty()) {
            index++
            continue
        }
        require(lines[index++] == PUBLIC_KEY_ARMOR_BEGIN)
        while (index < lines.size && lines[index].contains(':')) index++
        require(index < lines.size && lines[index++].isEmpty())
        val block = StringBuilder()
        while (index < lines.size && lines[index] != PUBLIC_KEY_ARMOR_END) {
            val line = lines[index++]
            if (line.startsWith('=')) {
                require(index < lines.size && lines[index] == PUBLIC_KEY_ARMOR_END)
            } else {
                block.append(line)
            }
        }
        require(index < lines.size && block.isNotEmpty())
        index++
        // Decode blocks separately: each base64 payload may end in padding.
        decoded += Base64.Default.decode(block.toString())
    }
    require(decoded.isNotEmpty())
    try {
        ByteArray(decoded.sumOf { it.size }).also { output ->
            var offset = 0
            decoded.forEach {
                it.copyInto(output, offset)
                offset += it.size
            }
        }
    } finally {
        decoded.eraseAll()
    }
}.getOrNull()
