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

import app.keemobile.kotpass.cryptography.intToLittleEndian
import app.keemobile.kotpass.cryptography.littleEndianToLong
import app.keemobile.kotpass.cryptography.longToLittleEndian

private const val Argon2BlockSize = 1024
private const val Argon2QwordsInBlock = Argon2BlockSize / 8
private const val Argon2AddressesInBlock = 128
private const val Argon2PreHashDigestLength = 64
private const val Argon2PreHashSeedLength = 72
private const val Argon2SyncPoints = 4

// Minimum and maximum digest size in bytes
private const val MinOutLen = 4

// Minimum and maximum number of passes
private const val M32L = 0xFFFFFFFFL

private val ZeroBytes = ByteArray(4)

internal class Argon2Engine(
    private val variant: Variant = Variant.Argon2d,
    private val version: Version = Version.Ver13,
    private val salt: ByteArray,
    private val secret: ByteArray? = null,
    private val additional: ByteArray? = null,
    private val iterations: Int = 3,
    private val parallelism: Int = 1,
    private val memory: Int
) {
    private val blocks: Array<Block>
    private var segmentLength = 0
    private var laneLength = 0

    enum class Variant(val id: Int) {
        Argon2d(0x00),
        Argon2i(0x01),
        Argon2id(0x02)
    }

    enum class Version(val id: Int) {
        Ver10(0x10),
        Ver13(0x13);

        companion object {
            fun from(id: UInt) = when (id.toInt()) {
                Ver13.id -> Ver13
                else -> Ver10
            }
        }
    }

    init {
        /**
         * 2. Align memory size
         * Minimum memoryBlocks = 8L blocks, where L is the number of lanes
         */
        var memoryBlocks = memory
        if (memoryBlocks < 2 * Argon2SyncPoints * parallelism) {
            memoryBlocks = 2 * Argon2SyncPoints * parallelism
        }
        segmentLength = memoryBlocks / (parallelism * Argon2SyncPoints)
        laneLength = segmentLength * Argon2SyncPoints

        // Ensure that all segments have equal length
        memoryBlocks = segmentLength * (parallelism * Argon2SyncPoints)

        blocks = Array(memoryBlocks) { Block() }
    }

    fun generateBytes(
        password: ByteArray,
        out: ByteArray,
        outOff: Int = 0,
        outLen: Int = out.size
    ): Int {
        check(outLen >= MinOutLen) { "Output length less than $MinOutLen" }
        val tmpBlockBytes = ByteArray(Argon2BlockSize)
        initialize(tmpBlockBytes, password, outLen)
        fillMemoryBlocks()
        digest(tmpBlockBytes, out, outOff, outLen)
        reset()
        return outLen
    }

    private fun reset() = blocks.forEach(Block::clear)

    private fun fillMemoryBlocks() {
        val filler = FillBlock()
        val position = Position()
        for (pass in 0 until iterations) {
            position.pass = pass
            for (slice in 0 until Argon2SyncPoints) {
                position.slice = slice
                for (lane in 0 until parallelism) {
                    position.lane = lane
                    fillSegment(filler, position)
                }
            }
        }
    }

    private fun fillSegment(filler: FillBlock, position: Position) {
        var addressBlock: Block? = null
        var inputBlock: Block? = null
        val dataIndependentAddressing = isDataIndependentAddressing(position)
        val startingIndex = getStartingIndex(position)
        var currentOffset =
            position.lane * laneLength + position.slice * segmentLength + startingIndex
        var prevOffset = getPrevOffset(currentOffset)

        if (dataIndependentAddressing) {
            addressBlock = filler.addressBlock.clear()
            inputBlock = filler.inputBlock.clear()
            initAddressBlocks(filler, position, inputBlock, addressBlock)
        }
        val withXor = isWithXor(position)

        for (index in startingIndex until segmentLength) {
            val pseudoRandom = getPseudoRandom(
                filler,
                index,
                addressBlock,
                inputBlock,
                prevOffset,
                dataIndependentAddressing
            )
            val refLane = getRefLane(position, pseudoRandom)
            val refColumn = getRefColumn(position, index, pseudoRandom, refLane == position.lane)

            // 2 Creating a new block
            val prevBlock = blocks[prevOffset]
            val refBlock = blocks[laneLength * refLane + refColumn]
            val currentBlock = blocks[currentOffset]
            if (withXor) {
                filler.fillBlockWithXor(prevBlock, refBlock, currentBlock)
            } else {
                filler.fillBlock(prevBlock, refBlock, currentBlock)
            }
            prevOffset = currentOffset
            currentOffset++
        }
    }

    private fun isDataIndependentAddressing(position: Position): Boolean {
        return variant == Variant.Argon2i ||
            (variant == Variant.Argon2id && position.pass == 0 && position.slice < Argon2SyncPoints / 2)
    }

    private fun initAddressBlocks(
        filler: FillBlock,
        position: Position,
        inputBlock: Block,
        addressBlock: Block
    ) {
        inputBlock.v[0] = intToLong(position.pass)
        inputBlock.v[1] = intToLong(position.lane)
        inputBlock.v[2] = intToLong(position.slice)
        inputBlock.v[3] = intToLong(blocks.size)
        inputBlock.v[4] = intToLong(iterations)
        inputBlock.v[5] = intToLong(variant.id)

        if (position.pass == 0 && position.slice == 0) {
            // Don't forget to generate the first block of addresses:
            nextAddresses(filler, inputBlock, addressBlock)
        }
    }

    private fun isWithXor(position: Position): Boolean {
        return !(position.pass == 0 || version == Version.Ver10)
    }

    private fun getPrevOffset(currentOffset: Int): Int {
        return if (currentOffset % laneLength == 0) {
            // Last block in this lane
            currentOffset + laneLength - 1
        } else {
            // Previous block
            currentOffset - 1
        }
    }

    private fun nextAddresses(filler: FillBlock, inputBlock: Block, addressBlock: Block?) {
        inputBlock.v[6]++
        filler.fillBlock(inputBlock, addressBlock)
        filler.fillBlock(addressBlock, addressBlock)
    }

    /**
     * 1.2 Computing the index of the reference block
     * 1.2.1 Taking pseudo-random value from the previous block
     */
    private fun getPseudoRandom(
        filler: FillBlock,
        index: Int,
        addressBlock: Block?,
        inputBlock: Block?,
        prevOffset: Int,
        dataIndependentAddressing: Boolean
    ): Long {
        return if (dataIndependentAddressing) {
            val addressIndex = index % Argon2AddressesInBlock
            if (addressIndex == 0) {
                nextAddresses(filler, inputBlock!!, addressBlock)
            }
            addressBlock!!.v[addressIndex]
        } else {
            blocks[prevOffset].v[0]
        }
    }

    private fun getRefLane(position: Position, pseudoRandom: Long): Int {
        var refLane = ((pseudoRandom ushr 32) % parallelism).toInt()
        if (position.pass == 0 && position.slice == 0) {
            // Can not reference other lanes yet
            refLane = position.lane
        }
        return refLane
    }

    private fun getRefColumn(
        position: Position,
        index: Int,
        pseudoRandom: Long,
        sameLane: Boolean
    ): Int {
        val referenceAreaSize: Int
        val startPosition: Int

        if (position.pass == 0) {
            startPosition = 0
            referenceAreaSize = if (sameLane) {
                // The same lane => add current segment
                position.slice * segmentLength + index - 1
            } else {
                // pass == 0 && !sameLane => position.slice > 0
                position.slice * segmentLength + if (index == 0) -1 else 0
            }
        } else {
            startPosition = (position.slice + 1) * segmentLength % laneLength
            referenceAreaSize = if (sameLane) {
                laneLength - segmentLength + index - 1
            } else {
                laneLength - segmentLength + if (index == 0) -1 else 0
            }
        }
        var relativePosition = pseudoRandom and 0xFFFFFFFFL
        relativePosition = relativePosition * relativePosition ushr 32
        relativePosition = referenceAreaSize - 1 - (referenceAreaSize * relativePosition ushr 32)

        return (startPosition + relativePosition).toInt() % laneLength
    }

    private fun digest(tmpBlockBytes: ByteArray, out: ByteArray, outOff: Int, outLen: Int) {
        val finalBlock = blocks[laneLength - 1]

        // XOR the last blocks
        for (i in 1 until parallelism) {
            val lastBlockInLane = i * laneLength + (laneLength - 1)
            finalBlock.xorWith(blocks[lastBlockInLane])
        }
        finalBlock.toBytes(tmpBlockBytes)
        hash(tmpBlockBytes, out, outOff, outLen)
    }

    /**
     * H' - hash - variable length hash function
     */
    private fun hash(input: ByteArray, out: ByteArray, outOff: Int, outLen: Int) {
        val outLenBytes = ByteArray(4)
        intToLittleEndian(outLen, outLenBytes, 0)
        val blake2bLength = 64

        if (outLen <= blake2bLength) {
            val blake = Blake2bDigest(outLen * 8)
            blake.update(outLenBytes, 0, outLenBytes.size)
            blake.update(input, 0, input.size)
            blake.doFinal(out, outOff)
        } else {
            var digest = Blake2bDigest(blake2bLength * 8)
            val outBuffer = ByteArray(blake2bLength)

            // V1
            digest.update(outLenBytes, 0, outLenBytes.size)
            digest.update(input, 0, input.size)
            digest.doFinal(outBuffer, 0)
            val halfLen = blake2bLength / 2
            var outPos = outOff
            outBuffer.copyInto(out, outPos, 0, 0 + halfLen)
            outPos += halfLen
            val r = (outLen + 31) / 32 - 2
            var i = 2

            while (i <= r) {
                // V2 to Vr
                digest.update(outBuffer, 0, outBuffer.size)
                digest.doFinal(outBuffer, 0)
                outBuffer.copyInto(out, outPos, 0, 0 + halfLen)
                i++
                outPos += halfLen
            }
            val lastLength = outLen - 32 * r

            // Vr+1
            digest = Blake2bDigest(lastLength * 8)
            digest.update(outBuffer, 0, outBuffer.size)
            digest.doFinal(out, outPos)
        }
    }

    /*
     * H0 = H64(p, τ, m, t, v, y, |P|, P, |S|, S, |L|, K, |X|, X)
     * -> 64 byte (ARGON2_PREHASH_DIGEST_LENGTH)
     */
    private fun initialize(tmpBlockBytes: ByteArray, password: ByteArray, outputLength: Int) {
        val blake = Blake2bDigest(Argon2PreHashDigestLength * 8)
        val values = intArrayOf(parallelism, outputLength, memory, iterations, version.id, variant.id)

        intToLittleEndian(values, tmpBlockBytes, 0)
        blake.update(tmpBlockBytes, 0, values.size * 4)

        addByteString(tmpBlockBytes, blake, password)
        addByteString(tmpBlockBytes, blake, salt)
        addByteString(tmpBlockBytes, blake, secret)
        addByteString(tmpBlockBytes, blake, additional)

        val initialHashWithZeros = ByteArray(Argon2PreHashSeedLength)
        blake.doFinal(initialHashWithZeros, 0)
        fillFirstBlocks(tmpBlockBytes, initialHashWithZeros)
    }

    /**
     * (H0 || 0 || i) 72 byte -> 1024 byte
     * (H0 || 1 || i) 72 byte -> 1024 byte
     */
    private fun fillFirstBlocks(tmpBlockBytes: ByteArray, initialHashWithZeros: ByteArray) {
        val initialHashWithOnes = ByteArray(Argon2PreHashSeedLength)
        initialHashWithZeros.copyInto(initialHashWithOnes, 0, 0, 0 + Argon2PreHashDigestLength)
        initialHashWithOnes[Argon2PreHashDigestLength] = 1

        for (i in 0 until parallelism) {
            intToLittleEndian(i, initialHashWithZeros, Argon2PreHashDigestLength + 4)
            intToLittleEndian(i, initialHashWithOnes, Argon2PreHashDigestLength + 4)
            hash(initialHashWithZeros, tmpBlockBytes, 0, Argon2BlockSize)
            blocks[i * laneLength + 0].fromBytes(tmpBlockBytes)
            hash(initialHashWithOnes, tmpBlockBytes, 0, Argon2BlockSize)
            blocks[i * laneLength + 1].fromBytes(tmpBlockBytes)
        }
    }

    private fun intToLong(x: Int): Long {
        return (x and M32L.toInt()).toLong()
    }

    @Suppress("PropertyName")
    private class FillBlock {
        var R = Block()
        var Z = Block()
        var addressBlock = Block()
        var inputBlock = Block()

        private fun applyBlake() {
            /*
             * Apply Blake2 on columns of 64-bit words: (0,1,...,15),
             * then (16,17,..31)... finally (112,113,...127)
             */
            for (i in 0..7) {
                val i16 = 16 * i
                roundFunction(
                    Z,
                    i16, i16 + 1, i16 + 2,
                    i16 + 3, i16 + 4, i16 + 5,
                    i16 + 6, i16 + 7, i16 + 8,
                    i16 + 9, i16 + 10, i16 + 11,
                    i16 + 12, i16 + 13, i16 + 14,
                    i16 + 15
                )
            }

            /* Apply Blake2 on rows of 64-bit words: (0,1,16,17,...112,113), then
            (2,3,18,19,...,114,115).. finally (14,15,30,31,...,126,127) */
            for (i in 0..7) {
                val i2 = 2 * i
                roundFunction(
                    Z,
                    i2, i2 + 1, i2 + 16,
                    i2 + 17, i2 + 32, i2 + 33,
                    i2 + 48, i2 + 49, i2 + 64,
                    i2 + 65, i2 + 80, i2 + 81,
                    i2 + 96, i2 + 97, i2 + 112,
                    i2 + 113
                )
            }
        }

        fun fillBlock(Y: Block?, currentBlock: Block?) {
            Z.copyBlock(Y)
            applyBlake()
            currentBlock!!.xor(Y, Z)
        }

        fun fillBlock(X: Block?, Y: Block?, currentBlock: Block?) {
            R.xor(X, Y)
            Z.copyBlock(R)
            applyBlake()
            currentBlock!!.xor(R, Z)
        }

        fun fillBlockWithXor(X: Block?, Y: Block?, currentBlock: Block?) {
            R.xor(X, Y)
            Z.copyBlock(R)
            applyBlake()
            currentBlock!!.xorWith(R, Z)
        }
    }

    private class Block {
        private val Size = Argon2QwordsInBlock

        // 128 * 8 Byte QWords
        val v: LongArray = LongArray(Size)

        fun fromBytes(input: ByteArray) {
            require(input.size >= Argon2BlockSize) { "Input shorter than blocksize" }
            littleEndianToLong(input, 0, v)
        }

        fun toBytes(output: ByteArray) {
            require(output.size >= Argon2BlockSize) { "Output shorter than blocksize" }
            longToLittleEndian(v, output, 0)
        }

        fun copyBlock(other: Block?) {
            other!!.v.copyInto(v, 0, 0, 0 + Size)
        }

        fun xor(b1: Block?, b2: Block?) {
            val v0 = v
            val v1 = b1!!.v
            val v2 = b2!!.v
            for (i in 0 until Size) {
                v0[i] = v1[i] xor v2[i]
            }
        }

        fun xorWith(b1: Block) {
            val v0 = v
            val v1 = b1.v
            for (i in 0 until Size) {
                v0[i] = v0[i] xor v1[i]
            }
        }

        fun xorWith(b1: Block, b2: Block) {
            val v0 = v
            val v1 = b1.v
            val v2 = b2.v
            for (i in 0 until Size) {
                v0[i] = v0[i] xor (v1[i] xor v2[i])
            }
        }

        fun clear(): Block {
            v.fill(0)
            return this
        }
    }

    private class Position {
        var pass = 0
        var lane = 0
        var slice = 0
    }

    private fun getStartingIndex(position: Position): Int {
        return if (position.pass == 0 && position.slice == 0) {
            2 // we have already generated the first two blocks
        } else {
            0
        }
    }

    private fun addByteString(
        tmpBlockBytes: ByteArray,
        digest: Blake2bDigest,
        octets: ByteArray?
    ) {
        if (octets == null) {
            digest.update(ZeroBytes, 0, 4)
            return
        }
        intToLittleEndian(octets.size, tmpBlockBytes, 0)
        digest.update(tmpBlockBytes, 0, 4)
        digest.update(octets, 0, octets.size)
    }

    companion object {
        private fun roundFunction(
            block: Block,
            v0: Int,
            v1: Int,
            v2: Int,
            v3: Int,
            v4: Int,
            v5: Int,
            v6: Int,
            v7: Int,
            v8: Int,
            v9: Int,
            v10: Int,
            v11: Int,
            v12: Int,
            v13: Int,
            v14: Int,
            v15: Int
        ) {
            val v = block.v
            F(v, v0, v4, v8, v12)
            F(v, v1, v5, v9, v13)
            F(v, v2, v6, v10, v14)
            F(v, v3, v7, v11, v15)
            F(v, v0, v5, v10, v15)
            F(v, v1, v6, v11, v12)
            F(v, v2, v7, v8, v13)
            F(v, v3, v4, v9, v14)
        }

        private fun F(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
            quarterRound(v, a, b, d, 32)
            quarterRound(v, c, d, b, 24)
            quarterRound(v, a, b, d, 16)
            quarterRound(v, c, d, b, 63)
        }

        private fun quarterRound(v: LongArray, x: Int, y: Int, z: Int, s: Int) {
            var a = v[x]
            val b = v[y]
            var c = v[z]
            a += b + 2 * (a and M32L) * (b and M32L)
            c = (c xor a).rotateRight(s)
            v[x] = a
            v[z] = c
        }
    }
}
