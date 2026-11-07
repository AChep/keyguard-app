package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.common.util.useAndClear
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val COSE_KEY_TYPE_EC2 = "EC2"
private const val COSE_LABEL_X = -2
private const val COSE_LABEL_Y = -3
private const val COSE_LABEL_D = -4
private const val P256_FIELD_BYTES = 32

/**
 * A P-256 PKCS#8 `PrivateKeyInfo` — version 0, `ecPublicKey` on `prime256v1`
 * — up to the 32 bytes of the scalar of its `ECPrivateKey`.
 */
private val P256_PKCS8_BEFORE_SCALAR =
    "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420".hexToByteArray()

/**
 * The `[1] publicKey` of that `ECPrivateKey`, up to the 64 bytes of the
 * uncompressed point's coordinates.
 */
private val P256_PKCS8_BEFORE_POINT = "a14403420004".hexToByteArray()

/**
 * The COSE key of a Proton Pass passkey, in serde's mirror of it: each enum is
 * `{"t": variant, "c": value}`, and [par] holds `[label, value]` pairs of such
 * enums.
 */
@Serializable
internal data class ProtonPassCoseKey(
    val kty: JsonElement? = null,
    val par: List<JsonArray> = emptyList(),
) {
    /**
     * The PKCS#8 of an ES256 key. Its public point goes in too, so the vault's
     * key check refuses one that is not on P-256 rather than store a key the
     * site cannot verify. An Ed25519 key, the other kind Proton makes, is
     * `null`.
     */
    fun p256Pkcs8OrNull(): ByteArray? {
        val ec2 = kty?.tagged("assign")?.stringOrNull() == COSE_KEY_TYPE_EC2
        val x = bytesOf(COSE_LABEL_X)
        val y = bytesOf(COSE_LABEL_Y)
        val point = if (x?.size == P256_FIELD_BYTES && y?.size == P256_FIELD_BYTES) x + y else null
        val d = bytesOf(COSE_LABEL_D)
            ?: return null
        return d.useAndClear {
            if (ec2 && point != null && d.size == P256_FIELD_BYTES) {
                concat(P256_PKCS8_BEFORE_SCALAR, d, P256_PKCS8_BEFORE_POINT, point)
            } else {
                null
            }
        }
    }

    /** The bytes of the parameter of the COSE [label], if it has any. */
    private fun bytesOf(
        label: Int,
    ): ByteArray? = par
        .firstOrNull { pair ->
            val name = pair.getOrNull(0)?.tagged("int") as? JsonPrimitive
            name?.intOrNull == label
        }
        ?.getOrNull(1)
        ?.tagged("bytes")
        ?.let { it as? JsonArray }
        // A value that is not a number refuses the whole array.
        ?.map { (it as? JsonPrimitive)?.intOrNull ?: -1 }
        ?.protonBytesOrNull()
}

/**
 * The bytes of a Rust `Vec<u8>`, which serde writes as an array of byte
 * values, or `null` if one is out of range.
 */
internal fun List<Int>.protonBytesOrNull(): ByteArray? = this
    .takeIf { values -> values.all { it in 0..UByte.MAX_VALUE.toInt() } }
    ?.let { values -> ByteArray(values.size) { values[it].toByte() } }

/** The value of a serde enum `{"t": [type], "c": value}` of that variant. */
private fun JsonElement.tagged(
    type: String,
): JsonElement? = (this as? JsonObject)
    ?.takeIf { it["t"]?.stringOrNull() == type }
    ?.get("c")

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Joins [parts] into one array, so no other copy of a secret part is left. */
private fun concat(
    vararg parts: ByteArray,
): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    parts.fold(0) { offset, part ->
        part.copyInto(out, offset)
        offset + part.size
    }
    return out
}
