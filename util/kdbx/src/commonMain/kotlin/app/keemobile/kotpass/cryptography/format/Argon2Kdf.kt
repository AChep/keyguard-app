package app.keemobile.kotpass.cryptography.format

import com.artemchep.keyguard.nativecrypto.NativeArgon2Mode
import com.artemchep.keyguard.nativecrypto.NativeArgon2Version
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

internal object Argon2Kdf {
    fun transformKey(
        variant: NativeArgon2Mode,
        version: NativeArgon2Version,
        password: ByteArray,
        secretKey: ByteArray?,
        additional: ByteArray?,
        salt: ByteArray,
        iterations: ULong,
        parallelism: UInt,
        memory: ULong
    ): ByteArray {
        return NativeCryptoPrimitives.argon2(
            mode = variant,
            version = version,
            seed = password,
            salt = salt,
            secret = secretKey,
            associatedData = additional,
            iterations = iterations.toInt(),
            parallelism = parallelism.toInt(),
            memoryKb = (memory / 1024UL).toInt(),
            length = 32,
        )
    }
}
