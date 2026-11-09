package com.artemchep.keyguard.feature.p2psync.model

private const val QUOTE: Byte = 0x22
private const val BACKSLASH: Byte = 0x5c
private const val OPEN_OBJECT: Byte = 0x7b
private const val CLOSE_OBJECT: Byte = 0x7d
private const val OPEN_ARRAY: Byte = 0x5b
private const val CLOSE_ARRAY: Byte = 0x5d
private const val COMMA: Byte = 0x2c
private const val COLON: Byte = 0x3a

/**
 * Limits on JSON from a peer, checked in one pass over its UTF-8 bytes before a parser
 * allocates anything. Bytes alone do not bound what a parser makes of them: the tree of
 * `[0,0,…]` takes about 40 times its bytes, so a small compressed message could fill the
 * memory. A tree takes at most about 110 bytes for each node counted here.
 *
 * @property maxDepth the deepest nesting of arrays and objects.
 * @property maxNodes the most `{`, `[`, `,` and `:` outside strings. A parser makes a value or
 *   an object member for each, and one more for the last item of each array or object.
 */
class JsonLimits(
    val maxDepth: Int,
    val maxNodes: Int,
) {
    /**
     * `true` if the JSON [bytes] nest no deeper than [maxDepth] and hold at most [maxNodes]
     * nodes. It does not check that they are JSON. Every byte it looks for is ASCII, which is
     * never part of another UTF-8 sequence, so the bytes need no decoding first.
     */
    fun admits(bytes: ByteArray): Boolean {
        val scanner = JsonScanner()
        return bytes.all { byte ->
            scanner.next(byte)
            scanner.depth <= maxDepth && scanner.nodes <= maxNodes
        }
    }
}

/** The nodes of the JSON [bytes], see [JsonLimits.maxNodes]. */
internal fun jsonNodes(bytes: ByteArray): Int {
    val scanner = JsonScanner()
    bytes.forEach(scanner::next)
    return scanner.nodes
}

/** Follows the nesting of JSON one byte at a time and counts its nodes, see [JsonLimits.maxNodes]. */
private class JsonScanner {
    var depth = 0
        private set

    var nodes = 0
        private set

    private var inString = false
    private var escaped = false

    fun next(byte: Byte) {
        when {
            escaped -> escaped = false
            inString -> {
                escaped = byte == BACKSLASH
                inString = byte != QUOTE
            }

            byte == QUOTE -> inString = true
            byte == OPEN_OBJECT || byte == OPEN_ARRAY -> {
                depth++
                nodes++
            }

            byte == CLOSE_OBJECT || byte == CLOSE_ARRAY -> depth--
            byte == COMMA || byte == COLON -> nodes++
        }
    }
}
