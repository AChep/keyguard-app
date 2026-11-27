package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.MasterKdfOutOfMemoryException
import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import kotlinx.coroutines.Dispatchers

class GenerateMasterHashUtils(
    private val cryptoGenerator: CryptoGenerator,
) {
    companion object {
        private const val HASH_ITERATIONS_V0 = 100000
    }

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        kdf: MasterKdf,
    ): IO<ByteArray> = ioEffect(Dispatchers.Default) {
        // Since version 1 the master key hashing uses Argon2id
        // with larger than the current recommendation parameters:
        // https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#introduction
        cryptoGenerator.deriveMasterBytes(
            password = password,
            salt = salt,
            kdf = kdf,
            pbkdf2Iterations = HASH_ITERATIONS_V0,
            type = "master-hash",
        )
    }
}

/**
 * Derives the master bytes with the [kdf]: PBKDF2 with [pbkdf2Iterations]
 * for the legacy derivation, Argon2id otherwise. An unsupported
 * derivation throws an [UnsupportedMasterKdfVersionException] of the [type].
 */
internal fun CryptoGenerator.deriveMasterBytes(
    password: ByteArray,
    salt: ByteArray,
    kdf: MasterKdf,
    pbkdf2Iterations: Int,
    type: String,
): ByteArray = when (kdf) {
    is MasterKdf.Pbkdf2Legacy -> pbkdf2(
        seed = password,
        salt = salt,
        iterations = pbkdf2Iterations,
    )

    is MasterKdf.Argon2id -> masterArgon2id(
        password = password,
        salt = salt,
        kdf = kdf,
    )

    is MasterKdf.Unsupported -> throw UnsupportedMasterKdfVersionException(
        version = kdf.version,
        type = type,
    )
}

/**
 * Derives with the Argon2id [kdf]. A native allocation failure
 * becomes a [MasterKdfOutOfMemoryException].
 */
internal fun CryptoGenerator.masterArgon2id(
    password: ByteArray,
    salt: ByteArray,
    kdf: MasterKdf.Argon2id,
): ByteArray = try {
    argon2(
        mode = Argon2Mode.ARGON2_ID,
        seed = password,
        salt = salt,
        iterations = kdf.iterations,
        memoryKb = kdf.memoryKib,
        parallelism = kdf.parallelism,
    )
} catch (e: NativeCryptoException) {
    if (e.code == NativeCryptoErrorCode.RESOURCE_LIMIT) {
        throw MasterKdfOutOfMemoryException(e)
    }
    throw e
}
