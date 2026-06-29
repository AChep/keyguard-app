/**
 * The Bouncy Castle License
 *
 * Copyright (c) 2000-2021 The Legion Of The Bouncy Castle Inc. (https://www.bouncycastle.org)
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
 * to whom the Software is furnished to do so,
 * subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package app.keemobile.kotpass.cryptography.engines

import app.keemobile.kotpass.cryptography.littleEndianToLong
import app.keemobile.kotpass.cryptography.longToLittleEndian

/**
 * Implementation of the cryptographic hash function Blakbe2b.
 *
 *
 * Blake2b offers a built-in keying mechanism to be used directly
 * for authentication ("Prefix-MAC") rather than a HMAC construction.
 *
 *
 * Blake2b offers a built-in support for a salt for randomized hashing
 * and a personal string for defining a unique hash function for each application.
 *
 *
 * BLAKE2b is optimized for 64-bit platforms and produces digests of any size
 * between 1 and 64 bytes.
 */
internal class Blake2bDigest {
    private var digestSize = 64 // 1- 64 bytes
    private var keyLength = 0 // 0 - 64 bytes for keyed hashing for MAC
    private var salt: ByteArray? = null // new byte[16];
    private var personalization: ByteArray? = null // new byte[16];

    private var key: ByteArray? = null

    /**
     * Tree hashing parameters:
     * Because this class does not implement the Tree Hashing Mode,
     * these parameters can be treated as constants (see init() function)
     *
     * private int fanout = 1;
     * 0-255 private int depth = 1;
     * 1-255 private int leafLength= 0;
     * private long nodeOffset = 0L;
     * private int nodeDepth = 0;
     * private int innerHashLength = 0;
     * whenever this buffer overflows, it will be processed in the compress() function.
     * For performance issues, long messages will not use this buffer.
     */
    private val buffer: ByteArray // new byte[BLOCK_LENGTH_BYTES];

    // Position of last inserted byte:
    private var bufferPos = 0 // a value from 0 up to 128
    private val internalState = LongArray(16) // In the Blake2b paper it is

    // called: v
    private lateinit var chainValue: LongArray // state vector, in the Blake2b paper it

    // is called: h
    private var t0 = 0L // holds last significant bits, counter (counts bytes)
    private var t1 = 0L // counter: Length up to 2^128 are supported
    private var f0 = 0L // finalization flag, for last block: ~0L

    /**
     * Basic sized constructor - size in bits.
     *
     * @param digestSize size of the digest in bits
     */
    constructor(digestSize: Int = 512) {
        require(!(digestSize < 8 || digestSize > 512 || digestSize % 8 != 0)) {
            "BLAKE2b digest bit length must be a multiple of 8 and not greater than 512"
        }
        buffer = ByteArray(ByteLength)
        keyLength = 0
        this.digestSize = digestSize / 8
        initChainValue()
    }

    /**
     * Blake2b for authentication ("Prefix-MAC mode").
     * After calling the doFinal() method, the key will
     * remain to be used for further computations of
     * this instance.
     * The key can be overwritten using the clearKey() method.
     *
     * @param key A key up to 64 bytes or null
     */
    constructor(key: ByteArray?) {
        buffer = ByteArray(ByteLength)
        if (key != null) {
            this.key = ByteArray(key.size)
            key.copyInto(this.key!!, 0, 0, 0 + key.size)
            require(key.size <= 64) { "Keys > 64 are not supported" }
            keyLength = key.size
            key.copyInto(buffer, 0, 0, 0 + key.size)
            bufferPos = ByteLength // zero padding
        }
        digestSize = 64
        initChainValue()
    }

    /**
     * Blake2b with key, required digest length (in bytes), salt and personalization.
     * After calling the doFinal() method, the key, the salt and the personal string
     * will remain and might be used for further computations with this instance.
     * The key can be overwritten using the clearKey() method, the salt (pepper)
     * can be overwritten using the clearSalt() method.
     *
     * @param key             A key up to 64 bytes or null
     * @param digestLength    from 1 up to 64 bytes
     * @param salt            16 bytes or null
     * @param personalization 16 bytes or null
     */
    constructor(key: ByteArray?, digestLength: Int, salt: ByteArray?, personalization: ByteArray?) {
        buffer = ByteArray(ByteLength)
        require(digestLength in (1..64)) { "Invalid digest length (required: 1-64)." }
        digestSize = digestLength
        if (salt != null) {
            require(salt.size == 16) { "Salt length must be exactly 16 bytes." }
            this.salt = salt.copyOf()
        }
        if (personalization != null) {
            require(personalization.size == 16) { "Personalization length must be exactly 16 bytes." }
            this.personalization = personalization.copyOf()
        }
        if (key != null) {
            this.key = key.copyOf()
            require(key.size <= 64) { "Keys > 64 are not supported." }
            keyLength = key.size
            key.copyInto(buffer, 0, 0, 0 + key.size)
            bufferPos = ByteLength // zero padding
        }
        initChainValue()
    }

    private fun initChainValue() {
        chainValue = LongArray(8)
        chainValue[0] = (
            Blake2bIV[0]
                xor (digestSize or (keyLength shl 8) or 0x1010000).toLong()
            )
        // 0x1010000 = ((fanout << 16) | (depth << 24) | (leafLength << 32));
        // with fanout = 1; depth = 0; leafLength = 0;
        chainValue[1] = Blake2bIV[1] // ^ nodeOffset; with nodeOffset = 0;
        chainValue[2] = Blake2bIV[2] // ^ ( nodeDepth | (innerHashLength << 8) );
        // with nodeDepth = 0; innerHashLength = 0;
        chainValue[3] = Blake2bIV[3]
        chainValue[4] = Blake2bIV[4]
        chainValue[5] = Blake2bIV[5]

        if (salt != null) {
            chainValue[4] = chainValue[4] xor littleEndianToLong(salt!!, 0)
            chainValue[5] = chainValue[5] xor littleEndianToLong(salt!!, 8)
        }
        chainValue[6] = Blake2bIV[6]
        chainValue[7] = Blake2bIV[7]

        if (personalization != null) {
            chainValue[6] = chainValue[6] xor littleEndianToLong(personalization!!, 0)
            chainValue[7] = chainValue[7] xor littleEndianToLong(personalization!!, 8)
        }
    }

    private fun initializeInternalState() {
        chainValue.copyInto(internalState, 0, 0, 0 + chainValue.size)
        Blake2bIV.copyInto(internalState, chainValue.size, 0, 0 + 4)
        internalState[12] = t0 xor Blake2bIV[4]
        internalState[13] = t1 xor Blake2bIV[5]
        internalState[14] = f0 xor Blake2bIV[6]
        internalState[15] = Blake2bIV[7] // ^ f1 with f1 = 0
    }

    /**
     * Update the message digest with a single byte.
     *
     * @param b the input byte to be entered.
     */
    fun update(b: Byte) {
        val remainingLength = ByteLength - bufferPos
        if (remainingLength == 0) { // full buffer
            t0 += ByteLength.toLong()
            if (t0 == 0L) { // if message > 2^64
                t1++
            }
            compress(buffer, 0)
            buffer.fill(0)
            buffer[0] = b
            bufferPos = 1
        } else {
            buffer[bufferPos] = b
            bufferPos++
            return
        }
    }

    /**
     * Update the message digest with a block of bytes.
     *
     * @param message the byte array containing the data.
     * @param offset  the offset into the byte array where the data starts.
     * @param len     the length of the data.
     */
    fun update(message: ByteArray?, offset: Int, len: Int) {
        if (message == null || len == 0) {
            return
        }
        var remainingLength = 0 // left bytes of buffer
        if (bufferPos != 0) { // commenced, incomplete buffer

            // complete the buffer:
            remainingLength = ByteLength - bufferPos
            if (remainingLength < len) { // full buffer + at least 1 byte
                message.copyInto(buffer, bufferPos, offset, offset + remainingLength)
                t0 += ByteLength.toLong()
                if (t0 == 0L) { // if message > 2^64
                    t1++
                }
                compress(buffer, 0)
                bufferPos = 0
                buffer.fill(0)
            } else {
                message.copyInto(buffer, bufferPos, offset, offset + len)
                bufferPos += len
                return
            }
        }

        // Process blocks except last block (also if last block is full)
        val blockWiseLastPos = offset + len - ByteLength
        var messagePos = offset + remainingLength

        while (messagePos < blockWiseLastPos) {
            // Block wise 128 bytes without buffer:
            t0 += ByteLength.toLong()
            if (t0 == 0L) {
                t1++
            }
            compress(message, messagePos)
            messagePos += ByteLength
        }

        // Fill the buffer with left bytes, this might be a full block
        message.copyInto(buffer, 0, messagePos, messagePos + offset + len - messagePos)
        bufferPos += offset + len - messagePos
    }

    /**
     * Close the digest, producing the final digest value.
     * The doFinal call leaves the digest reset.
     * Key, salt and personal string remain.
     *
     * @param out       the array the digest is to be copied into.
     * @param outOffset the offset into the out array the digest is to start at.
     */
    fun doFinal(out: ByteArray, outOffset: Int): Int {
        f0 = -0x1L
        t0 += bufferPos.toLong()
        if (bufferPos > 0 && t0 == 0L) {
            t1++
        }
        compress(buffer, 0)
        buffer.fill(0)
        internalState.fill(0)
        var i = 0
        while (i < chainValue.size && i * 8 < digestSize) {
            val bytes = longToLittleEndian(chainValue[i])
            if (i * 8 < digestSize - 8) {
                bytes.copyInto(out, outOffset + i * 8, 0, 0 + 8)
            } else {
                bytes.copyInto(out, outOffset + i * 8, 0, 0 + digestSize - i * 8)
            }
            i++
        }
        chainValue.fill(0)
        reset()
        return digestSize
    }

    /**
     * Reset the digest back to it's initial state.
     * The key, the salt and the personal string will
     * remain for further computations.
     */
    fun reset() {
        bufferPos = 0
        f0 = 0L
        t0 = 0L
        t1 = 0L
        buffer.fill(0)
        key?.let {
            it.copyInto(buffer, 0, 0, 0 + it.size)
            bufferPos = ByteLength // Zero padding
        }
        initChainValue()
    }

    private fun compress(message: ByteArray, messagePos: Int) {
        initializeInternalState()
        val m = LongArray(16)
        for (j in 0..15) {
            m[j] = littleEndianToLong(message, messagePos + j * 8)
        }
        for (round in 0 until Rounds) {
            // G apply to columns of internalState:m[blake2b_sigma[round][2 * blockPos]] /+1
            G(m[Blake2bSigma[round][0].toInt()], m[Blake2bSigma[round][1].toInt()], 0, 4, 8, 12)
            G(m[Blake2bSigma[round][2].toInt()], m[Blake2bSigma[round][3].toInt()], 1, 5, 9, 13)
            G(m[Blake2bSigma[round][4].toInt()], m[Blake2bSigma[round][5].toInt()], 2, 6, 10, 14)
            G(m[Blake2bSigma[round][6].toInt()], m[Blake2bSigma[round][7].toInt()], 3, 7, 11, 15)

            // G apply to diagonals of internalState:
            G(m[Blake2bSigma[round][8].toInt()], m[Blake2bSigma[round][9].toInt()], 0, 5, 10, 15)
            G(m[Blake2bSigma[round][10].toInt()], m[Blake2bSigma[round][11].toInt()], 1, 6, 11, 12)
            G(m[Blake2bSigma[round][12].toInt()], m[Blake2bSigma[round][13].toInt()], 2, 7, 8, 13)
            G(m[Blake2bSigma[round][14].toInt()], m[Blake2bSigma[round][15].toInt()], 3, 4, 9, 14)
        }

        // Update chain values:
        for (offset in chainValue.indices) {
            chainValue[offset] =
                chainValue[offset] xor internalState[offset] xor internalState[offset + 8]
        }
    }

    private fun G(m1: Long, m2: Long, posA: Int, posB: Int, posC: Int, posD: Int) {
        internalState[posA] = internalState[posA] + internalState[posB] + m1
        internalState[posD] = (internalState[posD] xor internalState[posA]).rotateRight(32)
        internalState[posC] = internalState[posC] + internalState[posD]
        internalState[posB] = (internalState[posB] xor internalState[posC]).rotateRight(24) // replaces 25 of BLAKE
        internalState[posA] = internalState[posA] + internalState[posB] + m2
        internalState[posD] = (internalState[posD] xor internalState[posA]).rotateRight(16)
        internalState[posC] = internalState[posC] + internalState[posD]
        internalState[posB] = (internalState[posB] xor internalState[posC]).rotateRight(63) // replaces 11 of BLAKE
    }

    /**
     * Overwrite the key if it is no longer used
     */
    fun clearKey() {
        if (key != null) {
            key?.fill(0)
            buffer.fill(0)
        }
    }

    /**
     * Overwrite the salt (pepper) if it is secret and no longer used
     */
    fun clearSalt() = salt?.fill(0)

    companion object {
        private val Blake2bIV = longArrayOf(
            0x6a09e667f3bcc908L,
            -0x4498517a7b3558c5L,
            0x3c6ef372fe94f82bL,
            -0x5ab00ac5a0e2c90fL,
            0x510e527fade682d1L,
            -0x64fa9773d4c193e1L,
            0x1f83d9abfb41bd6bL,
            0x5be0cd19137e2179L
        )

        private val Blake2bSigma = arrayOf(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            byteArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            byteArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            byteArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            byteArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            byteArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            byteArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            byteArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            byteArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            byteArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            byteArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
        )

        private const val Rounds = 12

        /**
         * Size in bytes of the internal buffer the digest applies
         * it's compression function to.
         */
        const val ByteLength = 128
    }
}
