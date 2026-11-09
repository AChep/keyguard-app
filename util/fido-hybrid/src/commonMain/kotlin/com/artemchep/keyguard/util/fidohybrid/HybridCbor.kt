@file:Suppress("MagicNumber") // FIDO/CBOR wire constants and bounded frame sizes.

package com.artemchep.keyguard.util.fidohybrid

/** Definite-length CBOR with bounds for untrusted QR and CTAP inputs. */
internal object HybridCbor {
    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= 65_536)
        val reader = Reader(bytes)
        return reader.read(0).also { require(reader.position == bytes.size) }
    }

    fun encode(value: Any?): ByteArray = buildList<Byte> { write(value) }.toByteArray()

    private fun MutableList<Byte>.header(
        type: Int,
        value: Long,
    ) {
        require(value >= 0)
        val count =
            when {
                value < 24 -> 0
                value <= 0xff -> 1
                value <= 0xffff -> 2
                value <= 0xffff_ffff -> 4
                else -> 8
            }
        val extra =
            when (count) {
                0 -> value.toInt()
                1 -> 24
                2 -> 25
                4 -> 26
                else -> 27
            }
        add(((type shl 5) or extra).toByte())
        for (i in count - 1 downTo 0) add((value ushr (8 * i)).toByte())
    }

    private fun MutableList<Byte>.write(value: Any?) {
        when (value) {
            null -> {
                add(0xf6.toByte())
            }

            is Boolean -> {
                add(if (value) 0xf5.toByte() else 0xf4.toByte())
            }

            is Int -> {
                write(value.toLong())
            }

            is Long -> {
                if (value >= 0) header(0, value) else header(1, -(value + 1))
            }

            is ByteArray -> {
                header(2, value.size.toLong())
                addAll(value.toList())
            }

            is String -> {
                val bytes = value.encodeToByteArray()
                header(3, bytes.size.toLong())
                addAll(bytes.toList())
            }

            is List<*> -> {
                header(4, value.size.toLong())
                value.forEach { write(it) }
            }

            is Map<*, *> -> {
                writeMap(value)
            }

            else -> {
                error("Unsupported CBOR value")
            }
        }
    }

    private fun MutableList<Byte>.writeMap(value: Map<*, *>) {
        header(5, value.size.toLong())
        value.entries
            .map { encode(it.key) to it.value }
            .sortedWith { a, b -> compareKeys(a.first, b.first) }
            .forEach { (key, item) ->
                addAll(key.toList())
                write(item)
            }
    }

    private fun compareKeys(
        x: ByteArray,
        y: ByteArray,
    ): Int =
        if (x.size != y.size) {
            x.size.compareTo(y.size)
        } else {
            x.indices.firstNotNullOfOrNull { i ->
                ((x[i].toInt() and 255) - (y[i].toInt() and 255)).takeIf { it != 0 }
            } ?: 0
        }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        var position = 0
        private var remainingValues = 1024

        private fun byte(): Int {
            require(position < bytes.size)
            return bytes[position++].toInt() and 255
        }

        fun read(depth: Int): Any? {
            require(depth <= 8 && remainingValues-- > 0)
            val first = byte()
            val type = first ushr 5
            val extra = first and 31
            if (type == 7) {
                return when (extra) {
                    20 -> false
                    21 -> true
                    22 -> null
                    else -> error("Unsupported CBOR simple value")
                }
            }
            val value = argument(extra)
            return when (type) {
                0 -> value
                1 -> -1L - value
                2 -> readBytes(value)
                3 -> readText(value)
                4 -> List(size(value)) { read(depth + 1) }
                5 -> readMap(size(value), depth)
                else -> error("Unsupported CBOR type")
            }
        }

        private fun size(value: Long): Int {
            require(value <= bytes.size && value <= Int.MAX_VALUE)
            return value.toInt()
        }

        private fun readBytes(value: Long): ByteArray {
            val size = size(value)
            require(size <= bytes.size - position)
            return bytes.copyOfRange(position, position + size).also { position += size }
        }

        private fun readText(value: Long): String =
            try {
                readBytes(value).decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("Invalid CBOR text")
            }

        private fun readMap(
            size: Int,
            depth: Int,
        ): Map<Any, Any?> =
            buildMap {
                repeat(size) {
                    val key = read(depth + 1)
                    require(key is String || key is Long)
                    require(!containsKey(key))
                    put(key, read(depth + 1))
                }
            }

        private fun argument(extra: Int): Long {
            if (extra < 24) return extra.toLong()
            val count =
                when (extra) {
                    24 -> 1
                    25 -> 2
                    26 -> 4
                    27 -> 8
                    else -> error("Indefinite CBOR")
                }
            var number = 0L
            repeat(count) {
                require(number <= Long.MAX_VALUE ushr 8)
                number = (number shl 8) or byte().toLong()
            }
            return number
        }
    }
}
