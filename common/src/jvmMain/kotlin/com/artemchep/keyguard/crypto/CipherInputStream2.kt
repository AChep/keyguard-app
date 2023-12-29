package com.artemchep.keyguard.crypto

import org.bouncycastle.crypto.DataLengthException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.util.Arrays
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A CipherInputStream is composed of an InputStream and a cipher so that read() methods return data
 * that are read in from the underlying InputStream but have been additionally processed by the
 * Cipher. The cipher must be fully initialized before being used by a CipherInputStream.
 *
 *
 * For example, if the Cipher is initialized for decryption, the
 * CipherInputStream will attempt to read in data and decrypt them,
 * before returning the decrypted data.
 */
class CipherInputStream2 @JvmOverloads constructor(
    `is`: InputStream?,
    private val decoder: Decoder,
    bufSize: Int = INPUT_BUF_SIZE,
) : FilterInputStream(`is`) {
    interface Decoder {
        @Throws(DataLengthException::class, IllegalStateException::class)
        fun processBytes(
            `in`: ByteArray,
            inOff: Int,
            len: Int,
            out: ByteArray,
            outOff: Int,
        ): Int

        @Throws(
            DataLengthException::class,
            IllegalStateException::class,
            InvalidCipherTextException::class,
        )
        fun doFinal(
            out: ByteArray,
            outOff: Int,
        ): Int

        fun getOutputSize(
            length: Int,
        ): Int

        fun getUpdateOutputSize(
            length: Int,
        ): Int
    }

    private val inBuf: ByteArray
    private var buf: ByteArray? = null
    private var bufOff = 0
    private var maxBuf = 0
    private var finalized = false

    init {
        inBuf = ByteArray(bufSize)
    }

    /**
     * Read data from underlying stream and process with cipher until end of stream or some data is
     * available after cipher processing.
     *
     * @return -1 to indicate end of stream, or the number of bytes (> 0) available.
     */
    @Throws(IOException::class)
    private fun nextChunk(): Int {
        if (finalized) {
            return -1
        }
        bufOff = 0
        maxBuf = 0

        // Keep reading until EOF or cipher processing produces data
        while (maxBuf == 0) {
            val read = `in`.read(inBuf)
            if (read == -1) {
                finaliseCipher()
                return if (maxBuf == 0) {
                    -1
                } else {
                    maxBuf
                }
            }
            maxBuf = try {
                ensureCapacity(read, false)
                decoder.processBytes(inBuf, 0, read, buf!!, 0)
            } catch (e: Exception) {
                throw IOException("Error processing stream ", e)
            }
        }
        return maxBuf
    }

    @Throws(IOException::class)
    private fun finaliseCipher() {
        try {
            finalized = true
            ensureCapacity(0, true)
            maxBuf = decoder.doFinal(buf!!, 0)
        } catch (e: InvalidCipherTextException) {
            throw IOException("Error finalising cipher", e)
        } catch (e: Exception) {
            throw IOException("Error finalising cipher ", e)
        }
    }

    /**
     * Reads data from the underlying stream and processes it with the cipher until the cipher
     * outputs data, and returns the next available byte.
     *
     *
     * If the underlying stream is exhausted by this call, the cipher will be finalised.
     *
     *
     * @throws IOException if there was an error closing the input stream.
     * @throws IOException if the data read from the stream was invalid ciphertext
     * (e.g. the cipher is an AEAD cipher and the ciphertext tag check fails).
     */
    @Throws(IOException::class)
    override fun read(): Int {
        if (bufOff >= maxBuf) {
            if (nextChunk() < 0) {
                return -1
            }
        }
        return buf!![bufOff++].toInt() and 0xff
    }

    /**
     * Reads data from the underlying stream and processes it with the cipher until the cipher
     * outputs data, and then returns up to `b.length` bytes in the provided array.
     *
     *
     * If the underlying stream is exhausted by this call, the cipher will be finalised.
     *
     *
     * @param b the buffer into which the data is read.
     * @return the total number of bytes read into the buffer, or `-1` if there is no
     * more data because the end of the stream has been reached.
     * @throws IOException if there was an error closing the input stream.
     * @throws IOException if the data read from the stream was invalid ciphertext
     * (e.g. the cipher is an AEAD cipher and the ciphertext tag check fails).
     */
    @Throws(IOException::class)
    override fun read(
        b: ByteArray,
    ): Int {
        return read(b, 0, b.size)
    }

    /**
     * Reads data from the underlying stream and processes it with the cipher until the cipher
     * outputs data, and then returns up to `len` bytes in the provided array.
     *
     *
     * If the underlying stream is exhausted by this call, the cipher will be finalised.
     *
     *
     * @param b   the buffer into which the data is read.
     * @param off the start offset in the destination array `b`
     * @param len the maximum number of bytes read.
     * @return the total number of bytes read into the buffer, or `-1` if there is no
     * more data because the end of the stream has been reached.
     * @throws IOException if there was an error closing the input stream.
     * @throws IOException if the data read from the stream was invalid ciphertext
     * (e.g. the cipher is an AEAD cipher and the ciphertext tag check fails).
     */
    @Throws(IOException::class)
    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (bufOff >= maxBuf) {
            if (nextChunk() < 0) {
                return -1
            }
        }
        val toSupply = Math.min(len, available())
        System.arraycopy(buf!!, bufOff, b, off, toSupply)
        bufOff += toSupply
        return toSupply
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        throw IOException("skip(n) is not supported!")
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return maxBuf - bufOff
    }

    /**
     * Ensure the cipher text buffer has space sufficient to accept an upcoming output.
     *
     * @param updateSize  the size of the pending update.
     * @param finalOutput `true` if this the cipher is to be finalised.
     */
    private fun ensureCapacity(updateSize: Int, finalOutput: Boolean) {
        val bufLen: Int
        bufLen = if (finalOutput) {
            decoder.getOutputSize(updateSize)
        } else {
            decoder.getUpdateOutputSize(updateSize)
        }
        if (buf == null || buf!!.size < bufLen) {
            buf = ByteArray(bufLen)
        }
    }

    /**
     * Closes the underlying input stream and finalises the processing of the data by the cipher.
     *
     * @throws IOException if there was an error closing the input stream.
     * @throws IOException if the data read from the stream was invalid ciphertext
     * (e.g. the cipher is an AEAD cipher and the ciphertext tag check fails).
     */
    @Throws(IOException::class)
    override fun close() {
        try {
            `in`.close()
        } finally {
            if (!finalized) {
                // Reset the cipher, discarding any data buffered in it
                // Errors in cipher finalisation trump I/O error closing input
                finaliseCipher()
            }
        }
        bufOff = 0
        maxBuf = bufOff
        if (buf != null) {
            Arrays.fill(buf, 0.toByte())
            buf = null
        }
        Arrays.fill(inBuf, 0.toByte())
    }

    /**
     * Do nothing, as we do not support
     * marking.
     *
     * @see .markSupported
     */
    override fun mark(readlimit: Int) {}

    @Throws(IOException::class)
    override fun reset() {
        throw IOException("reset() is not supported!")
    }

    /**
     * Mark is not supported!
     */
    override fun markSupported(): Boolean {
        return false
    }

    companion object {
        private const val INPUT_BUF_SIZE = 2048
    }
}
