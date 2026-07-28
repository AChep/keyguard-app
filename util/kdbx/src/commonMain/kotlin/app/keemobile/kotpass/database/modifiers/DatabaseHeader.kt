package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.nextByteString
import app.keemobile.kotpass.cryptography.SecureRandom

fun KeePassDatabase.regenerateVectors(
    random: SecureRandom = SecureRandom(),
    cipherProviders: List<CipherProvider>
): KeePassDatabase {
    val ivLength = cipherProviders
        .firstOrNull { it.uuid == header.cipherId }
        ?.ivLength
        ?: throw FormatError.InvalidHeader("Unsupported cipher ID (${header.cipherId}).")

    return when (this) {
        is KeePassDatabase.Ver3x -> {
            copy(
                header = header.copy(
                    masterSeed = random.nextByteString(32),
                    encryptionIV = random.nextByteString(ivLength.toInt()),
                    transformSeed = random.nextByteString(32),
                    innerRandomStreamKey = random.nextByteString(32),
                    streamStartBytes = random.nextByteString(32)
                )
            )
        }
        is KeePassDatabase.Ver4x -> {
            copy(
                header = header.copy(
                    masterSeed = random.nextByteString(32),
                    encryptionIV = random.nextByteString(ivLength.toInt()),
                    kdfParameters = when (header.kdfParameters) {
                        is KdfParameters.Aes ->
                            header
                                .kdfParameters
                                .copy(seed = random.nextByteString(32))
                        is KdfParameters.Argon2 ->
                            header
                                .kdfParameters
                                .copy(salt = random.nextByteString(32))
                    }
                ),
                innerHeader = innerHeader.copy(
                    randomStreamKey = random.nextByteString(64)
                )
            )
        }
    }
}
