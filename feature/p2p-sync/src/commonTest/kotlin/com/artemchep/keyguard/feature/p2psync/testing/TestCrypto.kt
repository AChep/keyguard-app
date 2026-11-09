package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.writeBigEndian
import kotlin.random.Random

/**
 * TEST ONLY. A pure-Kotlin [P2pCrypto] for unit tests.
 *
 * SHA-256, HMAC-SHA256 and HKDF-SHA256 are real (and checked against the RFC vectors in
 * `TestCryptoTest`). Ed25519 is FAKE: the "public key" is SHA-256 of the private key and a
 * "signature" is an HMAC under the public key, so anyone holding the public key can forge
 * one. It only lets tests tell valid signatures from tampered ones. Never use it outside
 * tests.
 */
class TestCrypto(seed: Int = 0) : P2pCrypto {
    private val random = Random(seed)

    override fun sha256(bytes: ByteArray): ByteArray = Sha256.digest(bytes)

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt.takeIf { it.isNotEmpty() } ?: ByteArray(Sha256.DIGEST_SIZE), ikm)
        val out = ArrayList<Byte>(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            out.addAll(previous.toList())
            counter++
        }
        return out.take(length).toByteArray()
    }

    override fun ed25519Sign(privateKey: ByteArray, data: ByteArray): ByteArray =
        hmac(fakeEd25519PublicKey(privateKey), FAKE_SIGNATURE_DOMAIN + data)

    override fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        hmac(publicKey, FAKE_SIGNATURE_DOMAIN + data).contentEquals(signature)

    override fun randomBytes(count: Int): ByteArray = random.nextBytes(count)

    /** TEST ONLY: the fake Ed25519 public key of [privateKey]. */
    fun fakeEd25519PublicKey(privateKey: ByteArray): ByteArray = sha256(privateKey)

    /** TEST ONLY: HMAC-SHA256 of [data] under [key]. */
    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val block = if (key.size > Sha256.BLOCK_SIZE) Sha256.digest(key) else key
        val padded = block.copyOf(Sha256.BLOCK_SIZE)
        val inner = ByteArray(Sha256.BLOCK_SIZE) { (padded[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(Sha256.BLOCK_SIZE) { (padded[it].toInt() xor 0x5c).toByte() }
        return Sha256.digest(outer + Sha256.digest(inner + data))
    }

    private companion object {
        val FAKE_SIGNATURE_DOMAIN = "fake-ed25519".encodeToByteArray()
    }
}

/** TEST ONLY: FIPS 180-4 SHA-256. */
object Sha256 {
    const val DIGEST_SIZE = 32
    const val BLOCK_SIZE = 64

    private val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    private val initial = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )

    fun digest(message: ByteArray): ByteArray {
        val h = initial.copyOf()
        val padded = pad(message)
        val w = IntArray(64)
        for (block in padded.indices step BLOCK_SIZE) {
            for (t in 0 until 16) {
                w[t] = padded.readBigEndian(block + t * 4, Int.SIZE_BYTES).toInt()
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            compress(h, w)
        }
        val out = ByteArray(DIGEST_SIZE)
        h.forEachIndexed { index, value -> out.writeBigEndian(index * 4, Int.SIZE_BYTES, value.toLong()) }
        return out
    }

    private fun compress(h: IntArray, w: IntArray) {
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = hh + s1 + ch + k[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val paddedSize = ((message.size + 8) / BLOCK_SIZE + 1) * BLOCK_SIZE
        val padded = message.copyOf(paddedSize)
        padded[message.size] = 0x80.toByte()
        padded.writeBigEndian(paddedSize - Long.SIZE_BYTES, Long.SIZE_BYTES, bitLength)
        return padded
    }
}
