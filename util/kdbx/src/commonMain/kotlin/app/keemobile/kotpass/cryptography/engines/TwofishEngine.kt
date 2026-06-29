@file:Suppress(
    "ktlint:standard:max-line-length",
    "FunctionName",
    "MemberVisibilityCanBePrivate",
    "SpellCheckingInspection",
    "LocalVariableName"
)

/**
 * The Bouncy Castle License
 *
 * Copyright (c) 2000-2021 The Legion Of The Bouncy Castle Inc. (https://www.bouncycastle.org)
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package app.keemobile.kotpass.cryptography.engines

import app.keemobile.kotpass.cryptography.block.BlockCipher
import app.keemobile.kotpass.cryptography.intToLittleEndian
import app.keemobile.kotpass.cryptography.littleEndianToInt
import app.keemobile.kotpass.errors.CryptoError.InvalidDataLength

/**
 * Based on the Java reference implementation provided
 * by Bruce Schneier and developed by Raif S. Naffah.
 */
internal class TwofishEngine : BlockCipher {
    override val blockSize = BlockSize

    override val isEncrypting get() = encrypting

    private val gMDS0 = IntArray(MaxKeyBits)
    private val gMDS1 = IntArray(MaxKeyBits)
    private val gMDS2 = IntArray(MaxKeyBits)
    private val gMDS3 = IntArray(MaxKeyBits)

    private lateinit var gSubKeys: IntArray
    private lateinit var gSBox: IntArray

    private var k64Cnt = 0

    private var encrypting = false
    private var workingKey: ByteArray? = null

    init {
        val m1 = IntArray(2)
        val mX = IntArray(2)
        val mY = IntArray(2)
        var j: Int

        for (i in 0 until MaxKeyBits) {
            j = P[0][i] and 0xff
            m1[0] = j
            mX[0] = Mx_X(j) and 0xff
            mY[0] = Mx_Y(j) and 0xff

            j = P[1][i] and 0xff
            m1[1] = j
            mX[1] = Mx_X(j) and 0xff
            mY[1] = Mx_Y(j) and 0xff

            gMDS0[i] = m1[P_00] or (mX[P_00] shl 8) or (mY[P_00] shl 16) or (mY[P_00] shl 24)
            gMDS1[i] = mY[P_10] or (mY[P_10] shl 8) or (mX[P_10] shl 16) or (m1[P_10] shl 24)
            gMDS2[i] = mX[P_20] or (mY[P_20] shl 8) or (m1[P_20] shl 16) or (mY[P_20] shl 24)
            gMDS3[i] = mX[P_30] or (m1[P_30] shl 8) or (mY[P_30] shl 16) or (mX[P_30] shl 24)
        }
    }

    override fun init(encrypting: Boolean, key: ByteArray) {
        this.encrypting = encrypting
        this.workingKey = key

        val keyBits = key.size * 8
        if (keyBits !in setOf(128, 192, 256)) {
            throw InvalidDataLength("Twofish key length must be 128/192/256 bits")
        }

        k64Cnt = key.size / 8

        setKey(workingKey)
    }

    override fun processBlock(
        src: ByteArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int
    ): Int {
        checkNotNull(workingKey) { "Twofish is not initialised" }

        if (srcOffset + BlockSize > src.size) {
            throw InvalidDataLength("Input buffer is too short")
        }

        if (dstOffset + BlockSize > dst.size) {
            throw InvalidDataLength("Output buffer is too short")
        }

        if (encrypting) {
            encryptBlock(src, srcOffset, dst, dstOffset)
        } else {
            decryptBlock(src, srcOffset, dst, dstOffset)
        }

        return BlockSize
    }

    override fun reset() {
        if (workingKey != null) {
            setKey(workingKey)
        }
    }

    private fun setKey(key: ByteArray?) {
        val k32e = IntArray(MaxKeyBits / 64) // 4
        val k32o = IntArray(MaxKeyBits / 64) // 4

        val sBoxKeys = IntArray(MaxKeyBits / 64) // 4
        gSubKeys = IntArray(TotalSubkeys)

        for (i in 0 until k64Cnt) {
            val p = i * 8

            k32e[i] = littleEndianToInt(key!!, p)
            k32o[i] = littleEndianToInt(key, p + 4)

            sBoxKeys[k64Cnt - 1 - i] = RS_MDS_Encode(k32e[i], k32o[i])
        }

        var q: Int
        var A: Int
        var B: Int

        for (i in 0 until TotalSubkeys / 2) {
            q = i * SkStep
            A = F32(q, k32e)
            B = F32(q + SkBump, k32o)
            B = ((B).rotateLeft(8))
            A += B
            gSubKeys[i * 2] = A
            A += B
            gSubKeys[i * 2 + 1] = A shl SkRotl or (A ushr (32 - SkRotl))
        }

        val k0 = sBoxKeys[0]
        val k1 = sBoxKeys[1]
        val k2 = sBoxKeys[2]
        val k3 = sBoxKeys[3]
        var b0: Int
        var b1: Int
        var b2: Int
        var b3: Int
        gSBox = IntArray(4 * MaxKeyBits)

        for (i in 0 until MaxKeyBits) {
            b3 = i
            b2 = b3
            b1 = b2
            b0 = b1

            // @formatter:off
            when (k64Cnt and 3) {
                1 -> {
                    gSBox[i * 2] = gMDS0[P[P_01][b0] and 0xff xor b0(k0)]
                    gSBox[i * 2 + 1] = gMDS1[P[P_11][b1] and 0xff xor b1(k0)]
                    gSBox[i * 2 + 0x200] = gMDS2[P[P_21][b2] and 0xff xor b2(k0)]
                    gSBox[i * 2 + 0x201] = gMDS3[P[P_31][b3] and 0xff xor b3(k0)]
                }
                0 -> {
                    b0 = (P[P_04][b0] and 0xff) xor b0(k3)
                    b1 = (P[P_14][b1] and 0xff) xor b1(k3)
                    b2 = (P[P_24][b2] and 0xff) xor b2(k3)
                    b3 = (P[P_34][b3] and 0xff) xor b3(k3)
                    b0 = (P[P_03][b0] and 0xff) xor b0(k2)
                    b1 = (P[P_13][b1] and 0xff) xor b1(k2)
                    b2 = (P[P_23][b2] and 0xff) xor b2(k2)
                    b3 = (P[P_33][b3] and 0xff) xor b3(k2)

                    gSBox[i * 2] = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)]
                    gSBox[i * 2 + 1] = gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)]
                    gSBox[i * 2 + 0x200] = gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)]
                    gSBox[i * 2 + 0x201] = gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
                }
                3 -> {
                    b0 = (P[P_03][b0] and 0xff) xor b0(k2)
                    b1 = (P[P_13][b1] and 0xff) xor b1(k2)
                    b2 = (P[P_23][b2] and 0xff) xor b2(k2)
                    b3 = (P[P_33][b3] and 0xff) xor b3(k2)

                    gSBox[i * 2] = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)]
                    gSBox[i * 2 + 1] = gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)]
                    gSBox[i * 2 + 0x200] = gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)]
                    gSBox[i * 2 + 0x201] = gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
                }
                2 -> {
                    gSBox[i * 2] = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)]
                    gSBox[i * 2 + 1] = gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)]
                    gSBox[i * 2 + 0x200] = gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)]
                    gSBox[i * 2 + 0x201] = gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
                }
            }
            // @formatter:on
        }
    }

    private fun encryptBlock(
        src: ByteArray,
        srcIndex: Int,
        dst: ByteArray,
        dstIndex: Int
    ) {
        var x0 = littleEndianToInt(src, srcIndex) xor gSubKeys[InputWhiten]
        var x1 = littleEndianToInt(src, srcIndex + 4) xor gSubKeys[InputWhiten + 1]
        var x2 = littleEndianToInt(src, srcIndex + 8) xor gSubKeys[InputWhiten + 2]
        var x3 = littleEndianToInt(src, srcIndex + 12) xor gSubKeys[InputWhiten + 3]

        var k = RoundSubkeys
        var t0: Int
        var t1: Int
        var r = 0

        while (r < Rounds) {
            t0 = Fe32_0(x0)
            t1 = Fe32_3(x1)
            x2 = x2 xor t0 + t1 + gSubKeys[k++]
            x2 = ((x2).rotateRight(1))
            x3 = ((x3).rotateLeft(1)) xor (t0 + 2 * t1 + gSubKeys[k++])

            t0 = Fe32_0(x2)
            t1 = Fe32_3(x3)
            x0 = x0 xor t0 + t1 + gSubKeys[k++]
            x0 = ((x0).rotateRight(1))
            x1 = ((x1).rotateLeft(1)) xor (t0 + 2 * t1 + gSubKeys[k++])
            r += 2
        }

        intToLittleEndian(x2 xor gSubKeys[OutputWhiten], dst, dstIndex)
        intToLittleEndian(x3 xor gSubKeys[OutputWhiten + 1], dst, dstIndex + 4)
        intToLittleEndian(x0 xor gSubKeys[OutputWhiten + 2], dst, dstIndex + 8)
        intToLittleEndian(x1 xor gSubKeys[OutputWhiten + 3], dst, dstIndex + 12)
    }

    private fun decryptBlock(
        src: ByteArray,
        srcIndex: Int,
        dst: ByteArray,
        dstIndex: Int
    ) {
        var x2 = littleEndianToInt(src, srcIndex) xor gSubKeys[OutputWhiten]
        var x3 = littleEndianToInt(src, srcIndex + 4) xor gSubKeys[OutputWhiten + 1]
        var x0 = littleEndianToInt(src, srcIndex + 8) xor gSubKeys[OutputWhiten + 2]
        var x1 = littleEndianToInt(src, srcIndex + 12) xor gSubKeys[OutputWhiten + 3]

        var k = RoundSubkeys + 2 * Rounds - 1
        var t0: Int
        var t1: Int
        var r = 0

        while (r < Rounds) {
            t0 = Fe32_0(x2)
            t1 = Fe32_3(x3)
            x1 = x1 xor t0 + 2 * t1 + gSubKeys[k--]
            x0 = ((x0).rotateLeft(1)) xor (t0 + t1 + gSubKeys[k--])
            x1 = ((x1).rotateRight(1))

            t0 = Fe32_0(x0)
            t1 = Fe32_3(x1)
            x3 = x3 xor t0 + 2 * t1 + gSubKeys[k--]
            x2 = ((x2).rotateLeft(1)) xor (t0 + t1 + gSubKeys[k--])
            x3 = ((x3).rotateRight(1))
            r += 2
        }

        intToLittleEndian(x0 xor gSubKeys[InputWhiten], dst, dstIndex)
        intToLittleEndian(x1 xor gSubKeys[InputWhiten + 1], dst, dstIndex + 4)
        intToLittleEndian(x2 xor gSubKeys[InputWhiten + 2], dst, dstIndex + 8)
        intToLittleEndian(x3 xor gSubKeys[InputWhiten + 3], dst, dstIndex + 12)
    }

    private fun F32(x: Int, k32: IntArray): Int {
        var b0 = b0(x)
        var b1 = b1(x)
        var b2 = b2(x)
        var b3 = b3(x)
        val k0 = k32[0]
        val k1 = k32[1]
        val k2 = k32[2]
        val k3 = k32[3]

        var result = 0

        // @formatter:off
        when (k64Cnt and 3) {
            1 -> {
                result = gMDS0[P[P_01][b0] and 0xff xor b0(k0)] xor
                        gMDS1[P[P_11][b1] and 0xff xor b1(k0)] xor
                        gMDS2[P[P_21][b2] and 0xff xor b2(k0)] xor
                        gMDS3[P[P_31][b3] and 0xff xor b3(k0)]
            }

            0 -> {
                b0 = (P[P_04][b0] and 0xff) xor b0(k3)
                b1 = (P[P_14][b1] and 0xff) xor b1(k3)
                b2 = (P[P_24][b2] and 0xff) xor b2(k3)
                b3 = (P[P_34][b3] and 0xff) xor b3(k3)
                b0 = (P[P_03][b0] and 0xff) xor b0(k2)
                b1 = (P[P_13][b1] and 0xff) xor b1(k2)
                b2 = (P[P_23][b2] and 0xff) xor b2(k2)
                b3 = (P[P_33][b3] and 0xff) xor b3(k2)

                result = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)] xor
                    gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)] xor
                    gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)] xor
                    gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
            }

            3 -> {
                b0 = (P[P_03][b0] and 0xff) xor b0(k2)
                b1 = (P[P_13][b1] and 0xff) xor b1(k2)
                b2 = (P[P_23][b2] and 0xff) xor b2(k2)
                b3 = (P[P_33][b3] and 0xff) xor b3(k2)

                result = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)] xor
                    gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)] xor
                    gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)] xor
                    gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
            }

            2 -> {
                result = gMDS0[P[P_01][P[P_02][b0] and 0xff xor b0(k1)] and 0xff xor b0(k0)] xor
                        gMDS1[P[P_11][P[P_12][b1] and 0xff xor b1(k1)] and 0xff xor b1(k0)] xor
                        gMDS2[P[P_21][P[P_22][b2] and 0xff xor b2(k1)] and 0xff xor b2(k0)] xor
                        gMDS3[P[P_31][P[P_32][b3] and 0xff xor b3(k1)] and 0xff xor b3(k0)]
            }
        }
        // @formatter:on

        return result
    }

    private fun RS_MDS_Encode(k0: Int, k1: Int): Int {
        var r = k1
        for (i in 0..3) {
            r = RS_rem(r)
        }

        r = r xor k0
        for (i in 0..3) {
            r = RS_rem(r)
        }

        return r
    }

    private fun RS_rem(x: Int): Int {
        val b = (x ushr 24) and 0xff
        val g2 = ((b shl 1) xor (if (b and 0x80 != 0) RS_GF_FDBK else 0)) and 0xff
        val g3 = ((b ushr 1) xor (if (b and 0x01 != 0) (RS_GF_FDBK ushr 1) else 0)) xor g2

        return (x shl 8) xor (g3 shl 24) xor (g2 shl 16) xor (g3 shl 8) xor b
    }

    private fun LFSR1(x: Int): Int {
        return (x shr 1) xor
            (if (x and 0x01 != 0) GF256_FDBK_2 else 0)
    }

    private fun LFSR2(x: Int): Int {
        return (x shr 2) xor
            (if (x and 0x02 != 0) GF256_FDBK_2 else 0) xor
            (if (x and 0x01 != 0) GF256_FDBK_4 else 0)
    }

    private fun Mx_X(x: Int): Int = x xor LFSR2(x)

    private fun Mx_Y(x: Int): Int = x xor LFSR1(x) xor LFSR2(x)

    private fun b0(x: Int): Int = x and 0xff

    private fun b1(x: Int): Int = (x ushr 8) and 0xff

    private fun b2(x: Int): Int = (x ushr 16) and 0xff

    private fun b3(x: Int): Int = (x ushr 24) and 0xff

    private fun Fe32_0(x: Int): Int {
        return gSBox[0x000 + 2 * (x and 0xff)] xor
            gSBox[0x001 + 2 * ((x ushr 8) and 0xff)] xor
            gSBox[0x200 + 2 * ((x ushr 16) and 0xff)] xor
            gSBox[0x201 + 2 * ((x ushr 24) and 0xff)]
    }

    private fun Fe32_3(x: Int): Int {
        return gSBox[0x000 + 2 * ((x ushr 24) and 0xff)] xor
            gSBox[0x001 + 2 * (x and 0xff)] xor
            gSBox[0x200 + 2 * ((x ushr 8) and 0xff)] xor
            gSBox[0x201 + 2 * ((x ushr 16) and 0xff)]
    }

    companion object {
        private val P = arrayOf(
            intArrayOf(
                0xA9, 0x67, 0xB3, 0xE8,
                0x04, 0xFD, 0xA3, 0x76,
                0x9A, 0x92, 0x80, 0x78,
                0xE4, 0xDD, 0xD1, 0x38,
                0x0D, 0xC6, 0x35, 0x98,
                0x18, 0xF7, 0xEC, 0x6C,
                0x43, 0x75, 0x37, 0x26,
                0xFA, 0x13, 0x94, 0x48,
                0xF2, 0xD0, 0x8B, 0x30,
                0x84, 0x54, 0xDF, 0x23,
                0x19, 0x5B, 0x3D, 0x59,
                0xF3, 0xAE, 0xA2, 0x82,
                0x63, 0x01, 0x83, 0x2E,
                0xD9, 0x51, 0x9B, 0x7C,
                0xA6, 0xEB, 0xA5, 0xBE,
                0x16, 0x0C, 0xE3, 0x61,
                0xC0, 0x8C, 0x3A, 0xF5,
                0x73, 0x2C, 0x25, 0x0B,
                0xBB, 0x4E, 0x89, 0x6B,
                0x53, 0x6A, 0xB4, 0xF1,
                0xE1, 0xE6, 0xBD, 0x45,
                0xE2, 0xF4, 0xB6, 0x66,
                0xCC, 0x95, 0x03, 0x56,
                0xD4, 0x1C, 0x1E, 0xD7,
                0xFB, 0xC3, 0x8E, 0xB5,
                0xE9, 0xCF, 0xBF, 0xBA,
                0xEA, 0x77, 0x39, 0xAF,
                0x33, 0xC9, 0x62, 0x71,
                0x81, 0x79, 0x09, 0xAD,
                0x24, 0xCD, 0xF9, 0xD8,
                0xE5, 0xC5, 0xB9, 0x4D,
                0x44, 0x08, 0x86, 0xE7,
                0xA1, 0x1D, 0xAA, 0xED,
                0x06, 0x70, 0xB2, 0xD2,
                0x41, 0x7B, 0xA0, 0x11,
                0x31, 0xC2, 0x27, 0x90,
                0x20, 0xF6, 0x60, 0xFF,
                0x96, 0x5C, 0xB1, 0xAB,
                0x9E, 0x9C, 0x52, 0x1B,
                0x5F, 0x93, 0x0A, 0xEF,
                0x91, 0x85, 0x49, 0xEE,
                0x2D, 0x4F, 0x8F, 0x3B,
                0x47, 0x87, 0x6D, 0x46,
                0xD6, 0x3E, 0x69, 0x64,
                0x2A, 0xCE, 0xCB, 0x2F,
                0xFC, 0x97, 0x05, 0x7A,
                0xAC, 0x7F, 0xD5, 0x1A,
                0x4B, 0x0E, 0xA7, 0x5A,
                0x28, 0x14, 0x3F, 0x29,
                0x88, 0x3C, 0x4C, 0x02,
                0xB8, 0xDA, 0xB0, 0x17,
                0x55, 0x1F, 0x8A, 0x7D,
                0x57, 0xC7, 0x8D, 0x74,
                0xB7, 0xC4, 0x9F, 0x72,
                0x7E, 0x15, 0x22, 0x12,
                0x58, 0x07, 0x99, 0x34,
                0x6E, 0x50, 0xDE, 0x68,
                0x65, 0xBC, 0xDB, 0xF8,
                0xC8, 0xA8, 0x2B, 0x40,
                0xDC, 0xFE, 0x32, 0xA4,
                0xCA, 0x10, 0x21, 0xF0,
                0xD3, 0x5D, 0x0F, 0x00,
                0x6F, 0x9D, 0x36, 0x42,
                0x4A, 0x5E, 0xC1, 0xE0
            ),
            intArrayOf(
                0x75, 0xF3, 0xC6, 0xF4,
                0xDB, 0x7B, 0xFB, 0xC8,
                0x4A, 0xD3, 0xE6, 0x6B,
                0x45, 0x7D, 0xE8, 0x4B,
                0xD6, 0x32, 0xD8, 0xFD,
                0x37, 0x71, 0xF1, 0xE1,
                0x30, 0x0F, 0xF8, 0x1B,
                0x87, 0xFA, 0x06, 0x3F,
                0x5E, 0xBA, 0xAE, 0x5B,
                0x8A, 0x00, 0xBC, 0x9D,
                0x6D, 0xC1, 0xB1, 0x0E,
                0x80, 0x5D, 0xD2, 0xD5,
                0xA0, 0x84, 0x07, 0x14,
                0xB5, 0x90, 0x2C, 0xA3,
                0xB2, 0x73, 0x4C, 0x54,
                0x92, 0x74, 0x36, 0x51,
                0x38, 0xB0, 0xBD, 0x5A,
                0xFC, 0x60, 0x62, 0x96,
                0x6C, 0x42, 0xF7, 0x10,
                0x7C, 0x28, 0x27, 0x8C,
                0x13, 0x95, 0x9C, 0xC7,
                0x24, 0x46, 0x3B, 0x70,
                0xCA, 0xE3, 0x85, 0xCB,
                0x11, 0xD0, 0x93, 0xB8,
                0xA6, 0x83, 0x20, 0xFF,
                0x9F, 0x77, 0xC3, 0xCC,
                0x03, 0x6F, 0x08, 0xBF,
                0x40, 0xE7, 0x2B, 0xE2,
                0x79, 0x0C, 0xAA, 0x82,
                0x41, 0x3A, 0xEA, 0xB9,
                0xE4, 0x9A, 0xA4, 0x97,
                0x7E, 0xDA, 0x7A, 0x17,
                0x66, 0x94, 0xA1, 0x1D,
                0x3D, 0xF0, 0xDE, 0xB3,
                0x0B, 0x72, 0xA7, 0x1C,
                0xEF, 0xD1, 0x53, 0x3E,
                0x8F, 0x33, 0x26, 0x5F,
                0xEC, 0x76, 0x2A, 0x49,
                0x81, 0x88, 0xEE, 0x21,
                0xC4, 0x1A, 0xEB, 0xD9,
                0xC5, 0x39, 0x99, 0xCD,
                0xAD, 0x31, 0x8B, 0x01,
                0x18, 0x23, 0xDD, 0x1F,
                0x4E, 0x2D, 0xF9, 0x48,
                0x4F, 0xF2, 0x65, 0x8E,
                0x78, 0x5C, 0x58, 0x19,
                0x8D, 0xE5, 0x98, 0x57,
                0x67, 0x7F, 0x05, 0x64,
                0xAF, 0x63, 0xB6, 0xFE,
                0xF5, 0xB7, 0x3C, 0xA5,
                0xCE, 0xE9, 0x68, 0x44,
                0xE0, 0x4D, 0x43, 0x69,
                0x29, 0x2E, 0xAC, 0x15,
                0x59, 0xA8, 0x0A, 0x9E,
                0x6E, 0x47, 0xDF, 0x34,
                0x35, 0x6A, 0xCF, 0xDC,
                0x22, 0xC9, 0xC0, 0x9B,
                0x89, 0xD4, 0xED, 0xAB,
                0x12, 0xA2, 0x0D, 0x52,
                0xBB, 0x02, 0x2F, 0xA9,
                0xD7, 0x61, 0x1E, 0xB4,
                0x50, 0x04, 0xF6, 0xC2,
                0x16, 0x25, 0x86, 0x56,
                0x55, 0x09, 0xBE, 0x91
            )
        )

        private const val P_00 = 1
        private const val P_01 = 0
        private const val P_02 = 0
        private const val P_03 = P_01 xor 1
        private const val P_04 = 1

        private const val P_10 = 0
        private const val P_11 = 0
        private const val P_12 = 1
        private const val P_13 = P_11 xor 1
        private const val P_14 = 0

        private const val P_20 = 1
        private const val P_21 = 1
        private const val P_22 = 0
        private const val P_23 = P_21 xor 1
        private const val P_24 = 0

        private const val P_30 = 0
        private const val P_31 = 1
        private const val P_32 = 1
        private const val P_33 = P_31 xor 1
        private const val P_34 = 1

        private const val GF256_FDBK = 0x169
        private const val GF256_FDBK_2 = GF256_FDBK / 2
        private const val GF256_FDBK_4 = GF256_FDBK / 4

        private const val RS_GF_FDBK = 0x14D

        private const val BlockSize = 16 // In bytes (128 bits)

        private const val Rounds = 16
        private const val MaxRounds = 16 // In bytes (128 bits)

        private const val MaxKeyBits = 256

        private const val InputWhiten = 0
        private const val OutputWhiten = InputWhiten + BlockSize / 4 // 4
        private const val RoundSubkeys = OutputWhiten + BlockSize / 4 // 8

        private const val TotalSubkeys = RoundSubkeys + 2 * MaxRounds // 40

        private const val SkStep = 0x02020202
        private const val SkBump = 0x01010101
        private const val SkRotl = 9
    }
}
