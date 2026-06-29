package app.keemobile.kotpass.cryptography.block

import app.keemobile.kotpass.errors.CryptoError.InvalidDataLength

internal sealed class BlockCipherMode {
    abstract fun processBlock(
        cipher: BlockCipher,
        src: ByteArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int
    ): Int

    abstract fun reset()

    class CBC(private val iv: ByteArray) : BlockCipherMode() {
        private var cbcV = iv.copyOf()
        private var cbcNextV = ByteArray(iv.size) { 0x0 }

        override fun processBlock(
            cipher: BlockCipher,
            src: ByteArray,
            srcOffset: Int,
            dst: ByteArray,
            dstOffset: Int
        ): Int = if (cipher.isEncrypting) {
            encryptBlock(cipher, src, srcOffset, dst, dstOffset)
        } else {
            decryptBlock(cipher, src, srcOffset, dst, dstOffset)
        }

        private fun encryptBlock(
            cipher: BlockCipher,
            src: ByteArray,
            srcOffset: Int,
            dst: ByteArray,
            dstOffset: Int
        ): Int {
            if (srcOffset + cipher.blockSize > src.size) {
                throw InvalidDataLength("Input buffer is too short")
            }

            for (i in 0 until cipher.blockSize) {
                cbcV[i] = (cbcV[i].toInt() xor src[srcOffset + i].toInt()).toByte()
            }

            val length = cipher.processBlock(cbcV, 0, dst, dstOffset)

            dst.copyInto(cbcV, 0, dstOffset, dstOffset + cbcV.size)

            return length
        }

        private fun decryptBlock(
            cipher: BlockCipher,
            src: ByteArray,
            srcOffset: Int,
            dst: ByteArray,
            dstOffset: Int
        ): Int {
            if (srcOffset + cipher.blockSize > src.size) {
                throw InvalidDataLength("Input buffer is too short")
            }

            src.copyInto(cbcNextV, 0, srcOffset, srcOffset + cipher.blockSize)

            val length = cipher.processBlock(src, srcOffset, dst, dstOffset)

            for (i in 0 until cipher.blockSize) {
                dst[dstOffset + i] = (dst[dstOffset + i].toInt() xor cbcV[i].toInt()).toByte()
            }

            val tmp = cbcV
            cbcV = cbcNextV
            cbcNextV = tmp

            return length
        }

        override fun reset() {
            iv.copyInto(cbcV)
            cbcNextV.fill(0x0)
        }
    }
}
