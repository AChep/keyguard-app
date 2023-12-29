package com.artemchep.keyguard.crypto.util

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

fun hkdfSha256(
    seed: ByteArray,
    salt: ByteArray? = null,
    info: ByteArray? = null,
    length: Int = 32,
): ByteArray = hkdf(
    digest = SHA256Digest(),
    seed = seed,
    salt = salt,
    info = info,
    length = length,
)

private fun hkdf(
    digest: Digest,
    seed: ByteArray,
    salt: ByteArray?,
    info: ByteArray?,
    length: Int,
): ByteArray = run {
    val out = ByteArray(length)
    HKDFBytesGenerator(digest)
        .apply {
            val params = if (salt != null) {
                HKDFParameters(
                    seed,
                    salt,
                    info,
                )
            } else {
                HKDFParameters.skipExtractParameters(
                    seed,
                    info,
                )
            }
            init(params)
        }
        .generateBytes(out, 0, length)
    out
}
