package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.coroutines.Dispatchers

class GenerateMasterHashUtils(
    private val cryptoGenerator: CryptoGenerator,
) {
    companion object {
        private const val HASH_ITERATIONS_V0 = 100000
    }

    private val impls = listOf<GenerateMasterHashBase>(
        GenerateMasterHashBaseVersion0(),
        GenerateMasterHashBaseVersion1(),
    )

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        version: MasterKdfVersion,
    ): IO<ByteArray> = ioEffect(Dispatchers.Default) {
        val impl = impls.firstOrNull { it.version == version }
            ?: throw UnsupportedMasterKdfVersionException(
                version = version,
                type = "master-hash",
            )
        impl.encode(
            password = password,
            salt = salt,
        )
    }

    private interface GenerateMasterHashBase {
        val version: MasterKdfVersion

        suspend fun encode(
            password: ByteArray,
            salt: ByteArray,
        ): ByteArray
    }

    private inner class GenerateMasterHashBaseVersion0 : GenerateMasterHashBase {
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

    // As a version 1 we switch the master key hashing to use
    // Argon2id with larger than the current recommendation
    // parameters:
    // https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#introduction
    private inner class GenerateMasterHashBaseVersion1 : GenerateMasterHashBase {
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
