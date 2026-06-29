package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters.Aes
import app.keemobile.kotpass.extensions.clear
import com.artemchep.keyguard.util.foundation.crypto.sha256
import com.artemchep.keyguard.util.foundation.crypto.sha512

internal object KeyTransform {
    fun compositeKey(credentials: Credentials): ByteArray {
        val items = listOfNotNull(
            credentials.passphrase?.getBinary(),
            credentials.key?.getBinary()
        )
        val composite = when {
            items.isNotEmpty() -> items.reduce { a, b -> a + b }
            else -> ByteArray(0)
        }

        return sha256(composite)
            .also { composite.clear() }
    }

    fun transformedKey(
        kdfProvider: KdfProvider,
        header: DatabaseHeader,
        credentials: Credentials
    ): ByteArray {
        val compositeKey = compositeKey(credentials)

        return try {
            when (header) {
                is DatabaseHeader.Ver3x -> {
                    // KeePass 3.x supports only AES as key-derivation function
                    kdfProvider.transformKey(
                        kdfParameters = Aes(header.transformRounds, header.transformSeed),
                        compositeKey = compositeKey
                    )
                }
                is DatabaseHeader.Ver4x -> {
                    kdfProvider.transformKey(header.kdfParameters, compositeKey)
                }
            }
        } catch (error: Exception) {
            throw error
        } finally {
            compositeKey.clear()
        }
    }

    fun masterKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ) = sha256(masterSeed + transformedKey)

    fun hmacKey(
        masterSeed: ByteArray,
        transformedKey: ByteArray
    ): ByteArray {
        val combined = byteArrayOf(*masterSeed, *transformedKey, 0x01)
        return sha512(ByteArray(8) { 0xFF.toByte() } + sha512(combined))
            .also { combined.clear() }
    }
}
