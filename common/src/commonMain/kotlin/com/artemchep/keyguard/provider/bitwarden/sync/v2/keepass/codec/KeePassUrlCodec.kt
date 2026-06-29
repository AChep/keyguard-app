package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.REGEX_ANDROID_APP
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import java.util.Locale

/**
 * Parser contract for KeePass URL and Android app-origin fields.
 *
 * Fields consumed or written:
 *
 * | KeePass field                  | Direction | Parser use                                  |
 * |--------------------------------|-----------|---------------------------------------------|
 * | `URL`                          | both      | Primary web URI.                            |
 * | `KP2A_URL`, `KP2A_URL_<n>`     | decode    | KeePass2Android/KP2A web URI fields.        |
 * | `URL_<n>`                      | decode    | KeePassDX web URI fields.                   |
 * | `URL-<n>`                      | decode    | Strongbox web URI fields;                   |
 * | `URL <n>`                      | decode    | KeePassium web URI fields.                  |
 * | `AndroidApp`, `AndroidApp_<n>` | decode    | KeePassDX package-name app origins.         |
 * | `AndroidApp Signature*`        | both      | KeePassDX app signing SHA-256 fingerprints. |
 * | `AndroidApp<n>`                | decode    | KeePass2Android `androidapp://` origins.    |
 * | `<uri field>_MATCH_TYPE`       | both      | Keyguard URI match type.                    |
 *
 * Encode skips empty URI values, writes web URIs to `URL` / `KP2A_URL_<index>`,
 * writes Android app URIs to `AndroidApp` / `AndroidApp_<index>`, and writes
 * known match types beside each URI using the `_MATCH_TYPE` suffix.
 *
 * Decode orders results by client family, numeric ordinal, then original field
 * order. Invalid ordinals, unsupported app-origin encodings, and unknown match
 * sidecars are left unconsumed so the top-level custom-field parser preserves
 * them.
 */
internal class KeePassUrlCodec {
    fun encode(
        uris: List<BitwardenCipher.Login.Uri>,
    ): List<KeePassFieldWrite> = buildList {
        val nonEmptyUris = uris
            .filter { uri -> !uri.uri.isNullOrEmpty() }

        nonEmptyUris
            .filterNot { uri -> uri.uri.orEmpty().isAndroidAppUri() }
            .forEachIndexed { index, uri ->
                val key = if (index == 0) {
                    BasicField.Url()
                } else {
                    "$KP2A_URL_PREFIX$index"
                }
                addPlain(key, uri.uri)
                addPlain("$key$MATCH_TYPE_SUFFIX", uri.match?.verboseKey)
            }

        nonEmptyUris
            .filter { uri -> uri.uri.orEmpty().isAndroidAppUri() }
            .forEachIndexed { index, uri ->
                val key = androidAppFieldKey(index)
                val packageName = uri.uri.orEmpty().removePrefix(PROTOCOL_ANDROID_APP)
                addPlain(key, packageName)
                addPlain("$key$MATCH_TYPE_SUFFIX", uri.match?.verboseKey)

                val signatures = uri.signatures
                    .mapNotNull { signature ->
                        normalizeSha256FingerprintOrNull(signature.certFingerprintSha256)
                    }
                if (signatures.isNotEmpty()) {
                    addConcealed(androidAppSignatureFieldKey(index), signatures.joinToString(SIGNATURE_DELIMITER))
                }
            }
    }

    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
    ): List<BitwardenCipher.Login.Uri> =
        extract(remote)
            .onEach { uri ->
                uri.sourceKeys.forEach(scope::consumeField)
            }
            .map { uri ->
                BitwardenCipher.Login.Uri(
                    uri = uri.value,
                    match = uri.matchType,
                    signatures = uri.signatures,
                )
            }

    fun hasLoginUriFields(remote: Entry): Boolean =
        extract(remote).isNotEmpty()

    private fun extract(remote: Entry): List<DecodedUri> {
        val fields = remote.fields.entries
            .mapIndexed { index, entry ->
                FieldEntry(
                    key = entry.key,
                    value = entry.value,
                    order = index,
                )
            }
        return buildList {
            remote.fields.url
                ?.let { value ->
                    decodedWebUri(
                        remote = remote,
                        key = BasicField.Url(),
                        value = value,
                        group = GROUP_STANDARD_URL,
                        ordinal = 0,
                        order = -1,
                    )
                }
                ?.let(::add)

            fields
                .mapNotNull { field ->
                    val key = field.key
                    when {
                        key == KP2A_URL_FIELD_KEY -> decodedWebUri(
                            remote = remote,
                            key = key,
                            value = field.value,
                            group = GROUP_KP2A_URL,
                            ordinal = 0,
                            order = field.order,
                        )

                        key.startsWith(KP2A_URL_PREFIX) -> {
                            val ordinal = key
                                .removePrefix(KP2A_URL_PREFIX)
                                .toPositiveIntOrNull()
                                ?: return@mapNotNull null
                            decodedWebUri(
                                remote = remote,
                                key = key,
                                value = field.value,
                                group = GROUP_KP2A_URL,
                                ordinal = ordinal,
                                order = field.order,
                            )
                        }

                        key.startsWith(URL_UNDERSCORE_PREFIX) -> {
                            val ordinal = key
                                .removePrefix(URL_UNDERSCORE_PREFIX)
                                .toPositiveIntOrNull()
                                ?: return@mapNotNull null
                            decodedWebUri(
                                remote = remote,
                                key = key,
                                value = field.value,
                                group = GROUP_KEEPASS_DX_URL,
                                ordinal = ordinal,
                                order = field.order,
                            )
                        }

                        isStrongboxUrlKey(key) -> decodedWebUri(
                            remote = remote,
                            key = key,
                            value = field.value,
                            group = GROUP_STRONGBOX_URL,
                            ordinal = if (key == STRONGBOX_URL_2_FIELD_KEY) 0 else 1,
                            order = field.order,
                        )

                        key.startsWith(URL_SPACE_PREFIX) -> {
                            val ordinal = key
                                .removePrefix(URL_SPACE_PREFIX)
                                .toPositiveIntOrNull()
                                ?: return@mapNotNull null
                            decodedWebUri(
                                remote = remote,
                                key = key,
                                value = field.value,
                                group = GROUP_KEEPASSIUM_URL,
                                ordinal = ordinal,
                                order = field.order,
                            )
                        }

                        key == ANDROID_APP_FIELD_KEY -> decodedAndroidAppUri(
                            remote = remote,
                            key = key,
                            value = field.value,
                            signatureKey = androidAppSignatureFieldKey(0),
                            group = GROUP_KEEPASS_DX_ANDROID,
                            ordinal = 0,
                            order = field.order,
                            valueEncoding = AndroidAppValueEncoding.PACKAGE_NAME,
                        )

                        key.startsWith(ANDROID_APP_UNDERSCORE_PREFIX) -> {
                            val ordinal = key
                                .removePrefix(ANDROID_APP_UNDERSCORE_PREFIX)
                                .toPositiveIntOrNull()
                                ?: return@mapNotNull null
                            decodedAndroidAppUri(
                                remote = remote,
                                key = key,
                                value = field.value,
                                signatureKey = androidAppSignatureFieldKey(ordinal),
                                group = GROUP_KEEPASS_DX_ANDROID,
                                ordinal = ordinal,
                                order = field.order,
                                valueEncoding = AndroidAppValueEncoding.PACKAGE_NAME,
                            )
                        }

                        key.startsWith(ANDROID_APP_FIELD_KEY) -> {
                            val ordinal = key
                                .removePrefix(ANDROID_APP_FIELD_KEY)
                                .toPositiveIntOrNull()
                                ?: return@mapNotNull null
                            decodedAndroidAppUri(
                                remote = remote,
                                key = key,
                                value = field.value,
                                signatureKey = null,
                                group = GROUP_KEEPASS2ANDROID,
                                ordinal = ordinal,
                                order = field.order,
                                valueEncoding = AndroidAppValueEncoding.ANDROID_APP_URI,
                            )
                        }

                        else -> null
                    }
                }
                .let(::addAll)
        }
            .sortedWith(compareBy<DecodedUri> { it.group }.thenBy { it.ordinal }.thenBy { it.order })
    }

    private fun decodedWebUri(
        remote: Entry,
        key: String,
        value: EntryValue,
        group: Int,
        ordinal: Int,
        order: Int,
    ): DecodedUri? {
        val uri = value.content.trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return decodedUri(
            remote = remote,
            key = key,
            value = uri,
            group = group,
            ordinal = ordinal,
            order = order,
        )
    }

    private fun decodedAndroidAppUri(
        remote: Entry,
        key: String,
        value: EntryValue,
        signatureKey: String?,
        group: Int,
        ordinal: Int,
        order: Int,
        valueEncoding: AndroidAppValueEncoding,
    ): DecodedUri? {
        val uri = when (valueEncoding) {
            AndroidAppValueEncoding.PACKAGE_NAME -> value.content
                .trim()
                .takeIf { packageName -> packageName.isNotEmpty() }
                ?.let { packageName -> "$PROTOCOL_ANDROID_APP$packageName" }

            AndroidAppValueEncoding.ANDROID_APP_URI -> value.content.trim()
        }
            ?.takeIf { REGEX_ANDROID_APP.matches(it) }
            ?: return null
        val decodedSignatures = signatureKey
            ?.let { decodeAndroidAppSignatures(remote, it) }
        return decodedUri(
            remote = remote,
            key = key,
            value = uri,
            signatures = decodedSignatures?.signatures.orEmpty(),
            signatureSourceKey = decodedSignatures?.sourceKey,
            group = group,
            ordinal = ordinal,
            order = order,
        )
    }

    private fun decodedUri(
        remote: Entry,
        key: String,
        value: String,
        signatures: List<BitwardenCipher.Login.Uri.Signature> = emptyList(),
        signatureSourceKey: String? = null,
        group: Int,
        ordinal: Int,
        order: Int,
    ): DecodedUri {
        val matchTypeKey = "$key$MATCH_TYPE_SUFFIX"
        val matchType = remote.fields[matchTypeKey]
            ?.content
            ?.let(::decodeUrlMatchType)
        return DecodedUri(
            sourceKeys = listOfNotNull(
                key,
                matchTypeKey.takeIf { matchType != null },
                signatureSourceKey,
            ),
            group = group,
            ordinal = ordinal,
            order = order,
            value = value,
            matchType = matchType,
            signatures = signatures,
        )
    }

    private fun decodeUrlMatchType(
        matchType: String,
    ): BitwardenCipher.Login.Uri.MatchType? = BitwardenCipher.Login.Uri.MatchType.entries
        .firstOrNull { it.verboseKey == matchType }

    private fun String.toPositiveIntOrNull(): Int? =
        toIntOrNull()?.takeIf { it > 0 }

    private fun String.isAndroidAppUri(): Boolean =
        REGEX_ANDROID_APP.matches(this)

    private fun androidAppFieldKey(index: Int): String =
        if (index == 0) ANDROID_APP_FIELD_KEY else "$ANDROID_APP_UNDERSCORE_PREFIX$index"

    private fun androidAppSignatureFieldKey(index: Int): String =
        if (index == 0) ANDROID_APP_SIGNATURE_FIELD_KEY else "$ANDROID_APP_SIGNATURE_UNDERSCORE_PREFIX$index"

    private fun decodeAndroidAppSignatures(
        remote: Entry,
        key: String,
    ): DecodedSignatures? {
        val value = remote.fields[key]?.content ?: return null
        val signatures = value
            .split(SIGNATURE_DELIMITER)
            .map { fingerprint ->
                val normalized = normalizeSha256FingerprintOrNull(fingerprint)
                    ?: return null
                BitwardenCipher.Login.Uri.Signature(
                    certFingerprintSha256 = normalized,
                )
            }
        return signatures
            .takeIf { it.isNotEmpty() }
            ?.let { DecodedSignatures(sourceKey = key, signatures = it) }
    }

    private fun normalizeSha256FingerprintOrNull(
        fingerprint: String,
    ): String? {
        val hex = fingerprint
            .trim()
            .replace(":", "")
            .uppercase(Locale.US)
        if (hex.length != 64) {
            return null
        }
        if (!hex.all { char -> char in '0'..'9' || char in 'A'..'F' }) {
            return null
        }
        return hex
            .chunked(2)
            .joinToString(":")
    }

    private fun isStrongboxUrlKey(key: String): Boolean =
        key.startsWith(STRONGBOX_URL_PREFIX) &&
                key.length > STRONGBOX_URL_PREFIX.length &&
                !key.endsWith(MATCH_TYPE_SUFFIX)

    private data class FieldEntry(
        val key: String,
        val value: EntryValue,
        val order: Int,
    )

    private data class DecodedUri(
        val sourceKeys: List<String>,
        val group: Int,
        val ordinal: Int,
        val order: Int,
        val value: String,
        val matchType: BitwardenCipher.Login.Uri.MatchType?,
        val signatures: List<BitwardenCipher.Login.Uri.Signature>,
    )

    private data class DecodedSignatures(
        val sourceKey: String,
        val signatures: List<BitwardenCipher.Login.Uri.Signature>,
    )

    private enum class AndroidAppValueEncoding {
        PACKAGE_NAME,
        ANDROID_APP_URI,
    }

    private companion object {
        const val KP2A_URL_FIELD_KEY = "KP2A_URL"
        const val KP2A_URL_PREFIX = "KP2A_URL_"
        const val URL_UNDERSCORE_PREFIX = "URL_"
        const val URL_SPACE_PREFIX = "URL "
        const val STRONGBOX_URL_PREFIX = "URL-"
        const val STRONGBOX_URL_2_FIELD_KEY = "URL-2"
        const val ANDROID_APP_FIELD_KEY = "AndroidApp"
        const val ANDROID_APP_UNDERSCORE_PREFIX = "AndroidApp_"
        const val ANDROID_APP_SIGNATURE_FIELD_KEY = "AndroidApp Signature"
        const val ANDROID_APP_SIGNATURE_UNDERSCORE_PREFIX = "AndroidApp Signature_"
        const val SIGNATURE_DELIMITER = "##SIG##"
        const val MATCH_TYPE_SUFFIX = "_MATCH_TYPE"

        const val GROUP_STANDARD_URL = 0
        const val GROUP_KP2A_URL = 1
        const val GROUP_KEEPASS_DX_URL = 2
        const val GROUP_STRONGBOX_URL = 3
        const val GROUP_KEEPASSIUM_URL = 4
        const val GROUP_KEEPASS_DX_ANDROID = 5
        const val GROUP_KEEPASS2ANDROID = 6
    }
}
