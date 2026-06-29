@file:Suppress("MemberVisibilityCanBePrivate", "NAME_SHADOWING")

package app.keemobile.kotpass.cryptography.block

import app.keemobile.kotpass.cryptography.padding.BlockCipherPadding
import app.keemobile.kotpass.errors.CryptoError.InvalidDataLength
import kotlin.math.max

internal class PaddedBufferedBlockCipher(
    private val cipher: BlockCipher,
    private val mode: BlockCipherMode,
    private val padding: BlockCipherPadding
) {
    private val buf = ByteArray(cipher.blockSize)
    private var bufOff = 0
    private var forEncryption: Boolean = false

    fun init(encryption: Boolean, key: ByteArray) {
        this.forEncryption = encryption

        reset()

        cipher.init(encryption, key)
    }

    fun getOutputSize(len: Int): Int {
        val total = len + bufOff
        val leftOver = total % buf.size

        return when {
            leftOver == 0 && forEncryption -> total + buf.size
            leftOver == 0 -> total
            else -> total - leftOver + buf.size
        }
    }

    private fun getUpdateOutputSize(len: Int): Int {
        val total = len + bufOff

        return when (val leftOver = total % buf.size) {
            0 -> max(0, total - buf.size)
            else -> total - leftOver
        }
    }

    fun processBytes(src: ByteArray): ByteArray {
        val buf = ByteArray(getOutputSize(src.size))
        var len = processBytes(src, 0, src.size, buf, 0)
        len += doFinal(buf, len)

        return ByteArray(len).apply {
            buf.copyInto(this, endIndex = len)
        }
    }

    fun processBytes(
        src: ByteArray,
        srcOffset: Int,
        len: Int,
        dst: ByteArray,
        dstOffset: Int
    ): Int {
        var srcOff = srcOffset
        var len = len
        require(len >= 0) { "Can't have a negative input length!" }

        val blockSize = cipher.blockSize
        val length = getUpdateOutputSize(len)

        if (length > 0) {
            if (dstOffset + length > dst.size) {
                throw InvalidDataLength("Output buffer is too short")
            }
        }

        var resultLen = 0
        val gapLen = buf.size - bufOff

        if (len > gapLen) {
            src.copyInto(buf, bufOff, srcOff, srcOff + gapLen)

            resultLen += mode.processBlock(cipher, buf, 0, dst, dstOffset)

            bufOff = 0
            len -= gapLen
            srcOff += gapLen

            while (len > buf.size) {
                resultLen += mode.processBlock(cipher, src, srcOff, dst, dstOffset + resultLen)

                len -= blockSize
                srcOff += blockSize
            }
        }

        src.copyInto(buf, bufOff, srcOff, srcOff + len)

        bufOff += len

        return resultLen
    }

    fun doFinal(dst: ByteArray, dstOffset: Int): Int {
        var resultLen = 0

        if (forEncryption) {
            if (bufOff == cipher.blockSize) {
                if (dstOffset + 2 * cipher.blockSize > dst.size) {
                    reset()

                    throw InvalidDataLength("Output buffer is too short")
                }

                resultLen = mode.processBlock(cipher, buf, 0, dst, dstOffset)
                bufOff = 0
            }

            padding.addPadding(buf, bufOff)

            resultLen += mode.processBlock(cipher, buf, 0, dst, dstOffset + resultLen)

            reset()
        } else {
            if (bufOff == cipher.blockSize) {
                resultLen = mode.processBlock(cipher, buf, 0, buf, 0)
                bufOff = 0
            } else {
                reset()

                throw InvalidDataLength("Last block incomplete in decryption")
            }

            try {
                resultLen -= padding.padCount(buf)

                buf.copyInto(dst, dstOffset, 0, 0 + resultLen)
            } finally {
                reset()
            }
        }

        return resultLen
    }

    fun reset() {
        buf.fill(0x0)
        bufOff = 0

        cipher.reset()
        mode.reset()
    }
}
