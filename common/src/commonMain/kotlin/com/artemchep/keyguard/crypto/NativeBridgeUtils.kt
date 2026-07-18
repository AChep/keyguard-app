package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp

internal inline fun <T> List<GpgOpenPgpPublicKey>.withEncodedPublicKeys(
    block: (List<ByteArray>) -> T,
): T {
    val keyData = clampToNativeOpenPgpKeyLimit()
        .map { key -> key.armored.encodeToByteArray() }
    return try {
        block(keyData)
    } finally {
        keyData.eraseAll()
    }
}

internal fun List<ByteArray>.eraseAll() {
    forEach { value -> value.fill(0) }
}

/**
 * Keeps a key list within the current native OpenPGP request limit.
 *
 * This temporary policy preserves caller order and drops trailing documents. Consequently, an
 * oversized encryption request can omit recipients, while decryption or verification can omit the
 * matching key. A future implementation should preselect candidates from packet metadata and
 * reject oversized recipient sets instead of silently truncating them.
 */
internal fun <T> List<T>.clampToNativeOpenPgpKeyLimit(): List<T> =
    take(NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST)

internal fun throwLegacyAesUnsupported(): Nothing {
    throw IllegalArgumentException(
        "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
            "Please upgrade your vault to migrate to a newer encryption type!",
    )
}
