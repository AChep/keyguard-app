package com.artemchep.keyguard.common.service.gpgagent

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Minimal parser for canonical ("canonical transport") libgcrypt S-expressions —
 * the format gpg uses to hand a ciphertext to gpg-agent in a PKDECRYPT request.
 *
 * Grammar (no whitespace, no display hints):
 *  - a list is `(` <element>* `)`
 *  - an atom is `<decimal-length>:<raw bytes>` (the length counts raw bytes, not hex)
 *
 * Parsing is byte-accurate: atom payloads are kept as raw [ByteArray] slices so an
 * MPI carrying a leading sign byte or a point prefix survives untouched.
 */
internal sealed interface GpgSExpr {
    data class Atom(
        val bytes: ByteArray,
    ) : GpgSExpr {
        override fun equals(other: Any?): Boolean =
            this === other || other is Atom && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Listt(
        val items: List<GpgSExpr>,
    ) : GpgSExpr
}

/**
 * Serializes this S-expression back into the canonical ("canonical transport") libgcrypt
 * form — the inverse of [GpgCanonicalSExpr.parse]:
 *  - an atom becomes `<decimal-length>:<raw bytes>`
 *  - a list becomes `(` <element>* `)`
 */
internal fun GpgSExpr.encodeCanonical(): ByteArray {
    val out = Buffer()
    fun encode(node: GpgSExpr) {
        when (node) {
            is GpgSExpr.Atom -> {
                out.write(node.bytes.size.toString().encodeToByteArray())
                out.writeByte(':'.code.toByte())
                out.write(node.bytes)
            }

            is GpgSExpr.Listt -> {
                out.writeByte('('.code.toByte())
                node.items.forEach(::encode)
                out.writeByte(')'.code.toByte())
            }
        }
    }
    encode(this)
    return out.readByteArray()
}

internal object GpgCanonicalSExpr {
    fun parse(bytes: ByteArray): GpgSExpr {
        val cursor = Cursor(bytes)
        val node = cursor.readElement()
        // A canonical S-expression is exactly one element; trailing bytes are a
        // protocol error rather than something to silently ignore.
        if (!cursor.isAtEnd()) {
            throw IllegalArgumentException("Trailing bytes after S-expression at offset ${cursor.offset}")
        }
        return node
    }

    /**
     * Reads an `(enc-val (flags ...)? (<algo> (<name> <value>)...))` expression and
     * returns the algorithm name together with the named parameter values, skipping
     * any leading `(flags ...)` sub-list.
     */
    fun parseEncVal(node: GpgSExpr): Pair<String, Map<String, ByteArray>> {
        val outer = node as? GpgSExpr.Listt
            ?: throw IllegalArgumentException("enc-val must be a list")
        val head = outer.items.firstOrNull().asAtomString()
        if (head != "enc-val") {
            throw IllegalArgumentException("Expected enc-val, got: $head")
        }

        val algoList = outer.items
            .drop(1)
            .filterIsInstance<GpgSExpr.Listt>()
            .firstOrNull { sub ->
                val name = sub.items.firstOrNull().asAtomString()
                name == "rsa" || name == "ecdh" || name == "ecc"
            }
            ?: throw IllegalArgumentException("enc-val has no rsa/ecdh/ecc sub-list")

        val algoName = algoList.items.first().asAtomString()
            ?: throw IllegalArgumentException("enc-val algorithm name is not an atom")

        val params = LinkedHashMap<String, ByteArray>()
        for (param in algoList.items.drop(1)) {
            val paramList = param as? GpgSExpr.Listt ?: continue
            val name = paramList.items.getOrNull(0).asAtomString() ?: continue
            val value = (paramList.items.getOrNull(1) as? GpgSExpr.Atom)?.bytes ?: continue
            params[name] = value
        }
        return algoName to params
    }

    private fun GpgSExpr?.asAtomString(): String? =
        (this as? GpgSExpr.Atom)?.bytes?.decodeToString()

    private class Cursor(
        private val bytes: ByteArray,
    ) {
        var offset = 0
            private set

        fun isAtEnd(): Boolean = offset >= bytes.size

        fun readElement(): GpgSExpr {
            if (isAtEnd()) {
                throw IllegalArgumentException("Unexpected end of S-expression")
            }
            return if (bytes[offset] == '('.code.toByte()) {
                readList()
            } else {
                readAtom()
            }
        }

        private fun readList(): GpgSExpr.Listt {
            // Consume the opening '('.
            offset++
            val items = mutableListOf<GpgSExpr>()
            while (true) {
                if (isAtEnd()) {
                    throw IllegalArgumentException("Unterminated list in S-expression")
                }
                if (bytes[offset] == ')'.code.toByte()) {
                    offset++
                    break
                }
                items += readElement()
            }
            return GpgSExpr.Listt(items)
        }

        private fun readAtom(): GpgSExpr.Atom {
            var length = 0
            var sawDigit = false
            while (!isAtEnd() && bytes[offset] in '0'.code.toByte()..'9'.code.toByte()) {
                length = length * 10 + (bytes[offset] - '0'.code.toByte())
                // An atom can never be longer than the whole input. Bailing as
                // soon as the running length passes the input size keeps `length`
                // far from Int overflow (the `< 0` guard catches it defensively).
                if (length < 0 || length > bytes.size) {
                    throw IllegalArgumentException("Atom length out of range at offset $offset")
                }
                offset++
                sawDigit = true
            }
            if (!sawDigit) {
                throw IllegalArgumentException("Expected atom length at offset $offset")
            }
            if (isAtEnd() || bytes[offset] != ':'.code.toByte()) {
                throw IllegalArgumentException("Expected ':' after atom length at offset $offset")
            }
            // Consume the ':'.
            offset++
            if (offset + length > bytes.size) {
                throw IllegalArgumentException("Atom length $length exceeds remaining bytes at offset $offset")
            }
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return GpgSExpr.Atom(value)
        }
    }
}
