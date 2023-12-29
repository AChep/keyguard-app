package com.artemchep.keyguard.crypto.util

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter

fun pbkdf2Sha256(
    seed: ByteArray,
    salt: ByteArray,
    iterations: Int = 1,
    length: Int = 32,
): ByteArray = pbkdf2(
    digest = SHA256Digest(),
    seed = seed,
    salt = salt,
    iterations = iterations,
    length = length,
)

private fun pbkdf2(
    digest: Digest,
    seed: ByteArray,
    salt: ByteArray,
    iterations: Int,
    length: Int,
): ByteArray = run {
    val params = PKCS5S2ParametersGenerator(digest)
        .apply {
            init(
                seed,
                salt,
                iterations,
            )
        }
        .generateDerivedMacParameters(length * 8)
    (params as KeyParameter).key
}
