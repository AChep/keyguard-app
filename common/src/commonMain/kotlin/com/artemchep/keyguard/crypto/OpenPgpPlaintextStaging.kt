package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.stageTo
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
    stagingSpoolFactory: StagingSpoolFactory = DefaultStagingSpoolFactory(),
    block: (Sink) -> T,
): T = stagingSpoolFactory
    .create(
        purpose = StagingPurpose.OpenPgpPlaintext,
        limits = SpoolLimits(
            memoryBytes = minOf(memoryLimitBytes, maxPlaintextBytes),
            maximumBytes = maxPlaintextBytes,
        ),
        limitExceeded = { limit ->
            IOException(
                "Decrypted OpenPGP file exceeds the supported staging limit of $limit bytes",
            )
        },
    )
    .stageTo(output, block)

// Four native OpenPGP workers may run concurrently, bounding retained plaintext near 32 MiB.
internal const val MAX_IN_MEMORY_OPENPGP_PLAINTEXT_BYTES: Long = 8L * 1024L * 1024L

// File decryption is intentionally bounded independently of the compressed ciphertext size.
internal const val MAX_STAGED_OPENPGP_PLAINTEXT_BYTES: Long = 16L * 1024L * 1024L * 1024L
