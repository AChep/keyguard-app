package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.coroutines.Dispatchers

class GenerateMasterKeyUtils(
    private val cryptoGenerator: CryptoGenerator,
) {
    companion object {
        // Legacy v0 master key derivation parameters. Existing vaults
        // depend on this value to produce a stable unlock key.
        private const val HASH_ITERATIONS_V0 = 10000
    }

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        kdf: MasterKdf,
    ): IO<ByteArray> = ioEffect(Dispatchers.Default) {
        cryptoGenerator.deriveMasterBytes(
            password = password,
            salt = salt,
            kdf = kdf,
            pbkdf2Iterations = HASH_ITERATIONS_V0,
            type = "master-key",
        )
    }
}
