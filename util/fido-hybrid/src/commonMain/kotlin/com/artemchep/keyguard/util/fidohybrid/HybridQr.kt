@file:Suppress("MagicNumber") // FIDO/CBOR wire constants and bounded frame sizes.

package com.artemchep.keyguard.util.fidohybrid

/** Ephemeral QR material. Never persist or log this object or the original QR. */
class HybridQr private constructor(
    val peerIdentity: ByteArray,
    val secret: ByteArray,
) {
    fun clear() {
        peerIdentity.fill(0)
        secret.fill(0)
    }

    override fun toString(): String = "HybridQr(<redacted>)"

    companion object {
        fun parse(text: String): HybridQr {
            require(text.startsWith("FIDO:/") && text.length in 7..2048)
            val digits = text.substring(6)
            require(digits.all { it in '0'..'9' })
            val bytes = decodeDigits(digits)
            try {
                val map = HybridCbor.decode(bytes) as? Map<*, *> ?: error("Invalid QR map")
                val identity = map[0L] as? ByteArray ?: error("Missing QR key")
                val secret = map[1L] as? ByteArray ?: error("Missing QR secret")
                require(identity.size == 33 && identity[0].toInt() in 2..3 && secret.size == 16)
                return HybridQr(identity, secret)
            } finally {
                bytes.fill(0)
            }
        }

        /** Each 17-digit chunk holds 7 little-endian bytes; a shorter final chunk holds fewer. */
        private fun decodeDigits(digits: String): ByteArray {
            val chunks = digits.chunked(17)
            val output = ByteArray(chunks.sumOf { chunkBytes(it.length) })
            var offset = 0
            chunks.forEach { chunk ->
                val size = chunkBytes(chunk.length)
                var value = chunk.toLong()
                require(value < (1L shl (size * 8)))
                repeat(size) {
                    output[offset++] = value.toByte()
                    value = value ushr 8
                }
            }
            return output
        }

        private fun chunkBytes(digits: Int): Int =
            when (digits) {
                3 -> 1
                5 -> 2
                8 -> 3
                10 -> 4
                13 -> 5
                15 -> 6
                17 -> 7
                else -> error("Invalid QR digits")
            }
    }
}
