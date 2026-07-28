package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.foundation.io.AdaptiveSpool
import com.artemchep.keyguard.util.foundation.io.stageTo
import kotlinx.io.IOException
import kotlinx.io.Sink

/**
 * Withholds provisional OpenPGP plaintext until the complete message has authenticated.
 * Small payloads remain in erasable memory; larger payloads spill to encrypted private storage.
 */
internal fun <T> withStagedOpenPgpPlaintext(
    output: Sink,
    maxPlaintextBytes: Long = MAX_STAGED_OPENPGP_PLAINTEXT_BYTES,
    memoryLimitBytes: Long = MAX_IN_MEMORY_OPENPGP_PLAINTEXT_BYTES,
    block: (Sink) -> T,
): T = withStagedOpenPgpPlaintextUsing(
    output = output,
    maxPlaintextBytes = maxPlaintextBytes,
    memoryLimitBytes = memoryLimitBytes,
    spillFactory = {
        EncryptedTemporarySpillStorage.create(createPrivateTemporaryStorage())
    },
    block = block,
)

internal fun <T> withStagedOpenPgpPlaintextUsing(
    output: Sink,
    maxPlaintextBytes: Long,
    memoryLimitBytes: Long,
    spillFactory: () -> EncryptedTemporarySpillStorage,
    block: (Sink) -> T,
): T = output.use { destination ->
    AdaptiveSpool(
        memoryLimitBytes = minOf(memoryLimitBytes, maxPlaintextBytes),
        maximumBytes = maxPlaintextBytes,
        spillFactory = spillFactory,
        limitExceeded = { limit ->
            IOException(
                "Decrypted OpenPGP file exceeds the supported staging limit of $limit bytes",
            )
        },
    ).stageTo(destination, block)
}

// Four native OpenPGP workers may run concurrently, bounding retained plaintext near 32 MiB.
internal const val MAX_IN_MEMORY_OPENPGP_PLAINTEXT_BYTES: Long = 8L * 1024L * 1024L

// File decryption is intentionally bounded independently of the compressed ciphertext size.
internal const val MAX_STAGED_OPENPGP_PLAINTEXT_BYTES: Long = 16L * 1024L * 1024L * 1024L
