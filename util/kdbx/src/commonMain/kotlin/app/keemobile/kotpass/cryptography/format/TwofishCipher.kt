package app.keemobile.kotpass.cryptography.format

import app.keemobile.kotpass.cryptography.block.BlockCipherMode
import app.keemobile.kotpass.cryptography.block.PaddedBufferedBlockCipher
import app.keemobile.kotpass.cryptography.engines.TwofishEngine
import app.keemobile.kotpass.cryptography.padding.PKCS7Padding
import kotlin.uuid.Uuid

/**
 * Twofish is a symmetric key block cipher, with a block size of 128 bits
 * and key sizes up to 256 bits. Twofish uses pre-computed key-dependent
 * S-boxes and a complex key schedule.
 *
 * **Note:** Twofish is not part of the standard KDBX specification
 * and should be used only for compatibility reasons.
 */
object TwofishCipher : CipherProvider {
    override val uuid: Uuid = Uuid.parse("ad68f29f-576f-4bb9-a36a-d47af965346c")
    override val ivLength = 16U

    override fun encrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray = createPaddedCipher(BlockCipherMode.CBC(iv))
        .apply { init(true, key) }
        .processBytes(data)

    override fun decrypt(
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray
    ): ByteArray = createPaddedCipher(BlockCipherMode.CBC(iv))
        .apply { init(false, key) }
        .processBytes(data)

    private fun createPaddedCipher(mode: BlockCipherMode) =
        PaddedBufferedBlockCipher(TwofishEngine(), mode, PKCS7Padding)
}
