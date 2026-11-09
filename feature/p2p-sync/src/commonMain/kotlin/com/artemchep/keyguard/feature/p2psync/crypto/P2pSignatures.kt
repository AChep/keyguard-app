package com.artemchep.keyguard.feature.p2psync.crypto

import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Returns the bytes a signature of the [domain] covers: the domain, a line break and the [text]. */
internal fun signedBytes(domain: String, text: String): ByteArray = "$domain\n$text".encodeToByteArray()

/**
 * Returns the bytes a signature of the [domain] over the signed object [signed] covers: the
 * domain, a line break and the canonical JSON of `{"context": context, "object": signed}`.
 * The [context] names what the object does not carry itself, such as its group, so a
 * signature never moves to another one.
 */
internal fun signedBytes(
    domain: String,
    context: Map<String, String>,
    signed: JsonObject,
): ByteArray {
    val json = JsonObject(
        mapOf(
            "context" to JsonObject(context.mapValues { (_, value) -> JsonPrimitive(value) }),
            "object" to signed,
        ),
    )
    return signedBytes(domain, CanonicalJson.encode(json))
}

/** Signs [data] with the Ed25519 [privateKey], as base64url. */
internal fun P2pCrypto.signToBase64Url(privateKey: ByteArray, data: ByteArray): String =
    P2pEncoding.base64Url(ed25519Sign(privateKey, data))

/**
 * `true` if the [signature] is a valid Ed25519 signature of [data] by [publicKey], in
 * canonical base64url: unpadded, with no stray bits, see [P2pEncoding.canonicalBase64UrlOrNull].
 * A missing or empty key verifies nothing.
 */
internal fun P2pCrypto.verifyBase64Url(publicKey: ByteArray?, data: ByteArray, signature: String): Boolean {
    val key = publicKey?.takeIf { it.isNotEmpty() }
    val sig = P2pEncoding.canonicalBase64UrlOrNull(signature)
    return key != null && sig != null && ed25519Verify(key, data, sig)
}
