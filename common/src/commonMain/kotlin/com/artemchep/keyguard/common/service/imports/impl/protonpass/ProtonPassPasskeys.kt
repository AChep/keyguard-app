package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportUserHandle
import com.artemchep.keyguard.common.service.credentialexchange.impl.cxfStandardBase64
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportPasskey
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfFido2Extensions
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfFido2HmacCredentials
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.util.messagepack.MessagePackCodec
import com.artemchep.keyguard.util.messagepack.toJsonElement
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A real `content` is about 1 KB of base64. */
private const val MAX_CONTENT_CHARS = 16_384

/** A real `content` nests seven levels deep. */
private const val MAX_CONTENT_DEPTH = 16

/** The only format of `SerializedPassKey` there is. */
private const val CONTENT_FORMAT_VERSION = 1

/**
 * A passkey of a Proton Pass login. Proton describes it in these members; the
 * credential its authenticator signs with is in [content]: the standard base64
 * of a MessagePack [ProtonPassKeyEnvelope].
 *
 * See `proton-pass-common/src/passkey/passkey_handling.rs` and
 * `protonpasskey.rs`.
 */
@Serializable
private data class ProtonPassPasskey(
    val content: String? = null,
    val rpName: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val userHandle: String? = null,
    val userId: String? = null,
    val createTime: Long? = null,
) {
    /**
     * Rebuilds the passkey through the Credential Exchange import, which
     * validates each member as it does for any other app's passkey.
     */
    fun toFido2CredentialsOrNull(
        now: Instant,
        passkeyCrypto: PasskeyCrypto,
    ): DSecret.Login.Fido2Credentials? {
        val key = content?.let(::decodeProtonPassKey)
        val counter = (key?.counter ?: 0L)
            .takeIf { it in 0L..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val credential = key?.let { toCxfOrNull(it) }
        if (counter == null || credential == null) {
            return null
        }
        // A passkey Proton took in through Credential Exchange has no time.
        val creationDate = createTime
            ?.takeIf { it > 0 }
            ?.let(Instant::fromEpochSeconds)
            ?: now
        return mapImportPasskey(credential, creationDate, passkeyCrypto)
            ?.copy(
                rpName = rpName.orNullIfBlank(),
                counter = counter,
            )
    }

    /**
     * The passkey in the form Credential Exchange gives one, or `null` if a
     * member it has does not decode.
     */
    private fun toCxfOrNull(
        key: ProtonPassKey,
    ): CxfCredential.Passkey? {
        val credentialId = key.credentialId.toBase64UrlOrNull()
        val userHandle = when (val handle = userHandleOf(key)) {
            // Credential Exchange spells an absent handle as an empty one.
            CxfImportUserHandle.Absent -> ""
            is CxfImportUserHandle.Present -> handle.value
            CxfImportUserHandle.Undecodable -> null
        }
        val hmacSecret = key.extensions?.hmacSecret
        val extensions = hmacSecret?.toCxfOrNull()
        val extensionsDecoded = hmacSecret == null || extensions != null
        if (credentialId == null || userHandle == null || !extensionsDecoded) {
            return null
        }
        return key.key.p256Pkcs8OrNull()?.useAndClear { pkcs8 ->
            CxfCredential.Passkey(
                credentialId = credentialId,
                rpId = key.rpId,
                username = userName.orNullIfBlank() ?: key.userName.orEmpty(),
                userDisplayName = userDisplayName.orNullIfBlank() ?: key.userDisplayName.orEmpty(),
                userHandle = userHandle,
                key = PasskeyBase64.encodeToString(pkcs8),
                fido2Extensions = extensions,
            )
        }
    }

    /**
     * The user handle. An older passkey may lack `uhd`; the handle is the
     * user id of the registration, which Proton also keeps outside the
     * credential. Only an absent member gives way to the next: one that does
     * not decode costs the passkey instead.
     */
    private fun userHandleOf(
        key: ProtonPassKey,
    ): CxfImportUserHandle = listOf(
        key.userHandle.toUserHandle(),
        userHandle.base64ToUserHandle(),
        userId.base64ToUserHandle(),
    ).firstOrNull { it != CxfImportUserHandle.Absent }
        ?: CxfImportUserHandle.Absent
}

/**
 * `SerializedPassKey`: the MessagePack of a [ProtonPassKey] in [content]. A
 * Rust `Vec<u8>` is written as an array of byte values, as it is below.
 */
@Serializable
private data class ProtonPassKeyEnvelope(
    @SerialName("c")
    val content: List<Int>,
    @SerialName("v")
    val version: Int,
)

/** `ProtonPassKey`. Older passkeys lack the last three members. */
@Serializable
private data class ProtonPassKey(
    val key: ProtonPassCoseKey,
    @SerialName("cid")
    val credentialId: List<Int>,
    @SerialName("rid")
    val rpId: String,
    @SerialName("uhd")
    val userHandle: List<Int>? = null,
    @SerialName("cnt")
    val counter: Long? = null,
    @SerialName("ext")
    val extensions: ProtonPassKeyExtensions? = null,
    @SerialName("un")
    val userName: String? = null,
    @SerialName("udn")
    val userDisplayName: String? = null,
)

@Serializable
private data class ProtonPassKeyExtensions(
    @SerialName("hmac_secret")
    val hmacSecret: ProtonPassHmacSecret? = null,
)

/** The seeds of the hmac-secret extension, which the WebAuthn PRF uses. */
@Serializable
private data class ProtonPassHmacSecret(
    @SerialName("cred_with_uv")
    val credWithUv: List<Int>? = null,
    @SerialName("cred_without_uv")
    val credWithoutUv: List<Int>? = null,
) {
    /**
     * The seeds, or `null` if one does not decode. A missing seed without
     * user verification is derived, as Credential Exchange does; a broken one
     * is not, as the derived seed would differ from the one Proton had.
     */
    fun toCxfOrNull(): CxfFido2Extensions? {
        val withUv = credWithUv?.toBase64UrlOrNull()
        val withoutUv = credWithoutUv?.toBase64UrlOrNull()
        return if (withUv == null || credWithoutUv != null && withoutUv == null) {
            null
        } else {
            CxfFido2Extensions(
                hmacCredentials = CxfFido2HmacCredentials(
                    algorithm = CxfFido2HmacCredentials.ALGORITHM_HMAC_SHA256,
                    credWithUv = withUv,
                    credWithoutUv = withoutUv,
                ),
            )
        }
    }
}

/**
 * The passkeys of a login. One that cannot be rebuilt, such as an Ed25519 key
 * Keyguard cannot sign with, is counted.
 */
internal fun List<JsonElement>.protonPasskeys(
    title: String?,
    now: Instant,
    plan: ImportPlanBuilder,
    passkeyCrypto: PasskeyCrypto,
): List<DSecret.Login.Fido2Credentials> = mapNotNull { element ->
    val passkey = runCatchingNonFatal {
        importJson.decodeFromJsonElement<ProtonPassPasskey>(element)
            .toFido2CredentialsOrNull(now, passkeyCrypto)
    }.getOrNull()
    if (passkey == null) {
        plan.skip(ImportSkipReason.Passkey, title)
    }
    passkey
}

private fun decodeProtonPassKey(
    content: String,
): ProtonPassKey? {
    val envelope = content
        .takeIf { it.length <= MAX_CONTENT_CHARS }
        ?.let { cxfStandardBase64.decode(it) }
        ?.let { decodeMessagePack<ProtonPassKeyEnvelope>(it) }
        ?.takeIf { it.version == CONTENT_FORMAT_VERSION }
        ?: return null
    return envelope.content
        .protonBytesOrNull()
        ?.useAndClear { decodeMessagePack<ProtonPassKey>(it) }
}

/**
 * Decodes MessagePack through its JSON form; the input is checked first, as
 * the decoder trusts the lengths it declares.
 */
private inline fun <reified T> decodeMessagePack(
    bytes: ByteArray,
): T? {
    if (!MessagePackCodec.isWellFormed(bytes, MAX_CONTENT_DEPTH)) {
        return null
    }
    val json = MessagePackCodec.decodeDynamic(bytes).toJsonElement()
    return importJson.decodeFromJsonElement<T>(json)
}

private fun List<Int>.toBase64UrlOrNull(): String? = protonBytesOrNull()
    ?.useAndClear(PasskeyBase64::encodeToString)

/** A `uhd`; a missing or empty one is absent. */
private fun List<Int>?.toUserHandle(): CxfImportUserHandle = if (this == null) {
    CxfImportUserHandle.Absent
} else {
    protonBytesOrNull()?.toUserHandle()
        ?: CxfImportUserHandle.Undecodable
}

/** An outer member in standard base64; a missing or empty one is absent. */
private fun String?.base64ToUserHandle(): CxfImportUserHandle = if (isNullOrEmpty()) {
    CxfImportUserHandle.Absent
} else {
    runCatchingNonFatal { cxfStandardBase64.decode(this) }
        .getOrNull()
        ?.toUserHandle()
        ?: CxfImportUserHandle.Undecodable
}

private fun ByteArray.toUserHandle(): CxfImportUserHandle = useAndClear { bytes ->
    if (bytes.isEmpty()) {
        CxfImportUserHandle.Absent
    } else {
        CxfImportUserHandle.Present(PasskeyBase64.encodeToString(bytes))
    }
}
