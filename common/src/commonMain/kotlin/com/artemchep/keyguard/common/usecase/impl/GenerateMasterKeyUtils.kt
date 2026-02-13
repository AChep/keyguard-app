package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.MasterKdfVersion
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

    private val impls = listOf<GenerateMasterKeyBase>(
        GenerateMasterKeyBaseVersion0(),
        GenerateMasterKeyBaseVersion1(),
    )

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        version: MasterKdfVersion,
    ): IO<ByteArray> = ioEffect(Dispatchers.Default) {
        val impl = impls.firstOrNull { it.version == version }
            ?: throw UnsupportedMasterKdfVersionException(
                version = version,
                type = "master-key",
            )
        impl.encode(
            password = password,
            salt = salt,
        )
    }

    private interface GenerateMasterKeyBase {
        val version: MasterKdfVersion

        suspend fun encode(
            password: ByteArray,
            salt: ByteArray,
        ): ByteArray
    }

    private inner class GenerateMasterKeyBaseVersion0 : GenerateMasterKeyBase {
        override val version: MasterKdfVersion get() = MasterKdfVersion.V0

        override suspend fun encode(
            password: ByteArray,
            salt: ByteArray,
        ) = cryptoGenerator.pbkdf2(
            seed = password,
            salt = salt,
            iterations = HASH_ITERATIONS_V0,
        )
    }

    private inner class GenerateMasterKeyBaseVersion1 : GenerateMasterKeyBase {
        override val version: MasterKdfVersion get() = MasterKdfVersion.V1

        override suspend fun encode(
            password: ByteArray,
            salt: ByteArray,
        ) = run {
            cryptoGenerator.argon2(
                mode = Argon2Mode.ARGON2_ID,
                seed = password,
                salt = salt,
                iterations = 3,
                memoryKb = 65536, // 64mb
                parallelism = 4,
            )
        }
    }
}
