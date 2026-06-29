package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceCanonicalPaths
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.SourceBinding
import com.artemchep.keyguard.core.store.bitwarden.SourceProjectionId
import com.artemchep.keyguard.core.store.bitwarden.SourceRepresentation
import com.artemchep.keyguard.core.store.bitwarden.SourceRole
import kotlin.time.Instant

/**
 * Parser contract for the KeePassXC/KPEX passkey field layout.
 *
 * Fields consumed or written:
 *
 * | KeePass field                       | Direction | Parser use                         |
 * |-------------------------------------|-----------|------------------------------------|
 * | `KPEX_PASSKEY_USERNAME`             | both      | User name; falls back from KPXC key. |
 * | `KPXC_PASSKEY_USERNAME`             | decode    | Legacy username fallback.          |
 * | `KPEX_PASSKEY_CREDENTIAL_ID`        | both      | Base64url credential id.           |
 * | `KPEX_PASSKEY_GENERATED_USER_ID`    | decode    | Legacy credential-id fallback.     |
 * | `KPEX_PASSKEY_PRIVATE_KEY_PEM`      | both      | PKCS#8 PEM private key, concealed. |
 * | `KPEX_PASSKEY_RELYING_PARTY`        | both      | Relying-party id.                  |
 * | `KPEX_PASSKEY_USER_HANDLE`          | both      | Base64url user handle, concealed.  |
 * | `KPEX_PASSKEY_FLAG_BE`              | both      | Backup-eligible boolean.           |
 * | `KPEX_PASSKEY_FLAG_BS`              | both      | Backup-state boolean.              |
 * | `Passkey` field and `Passkey` tag   | decode    | Display markers consumed if seen.  |
 *
 * Decode requires username, credential id, private key, relying party, and user
 * handle to be valid. Because KPEX lacks full Bitwarden metadata, decode
 * reconstructs a public-key ES256/P-256 discoverable credential with counter
 * `0`, display name equal to username, and the supplied creation date. Backup
 * flags are stored in binding parameters.
 *
 * Encode writes KPEX only for a public-key ES256/P-256 discoverable credential
 * with valid base64url id/user handle, non-blank relying party, and a private
 * key convertible to PKCS#8 PEM. Other credentials are left to the parent
 * codec's concealed `FIDO2 Credentials Blob #<index>` fallback.
 */
internal class KeePassPasskeyCodec(
    private val base64Service: Base64Service,
) {
    fun decode(
        scope: DecodeToCipherScope,
        remote: Entry,
        creationDate: Instant,
    ): DecodedPasskeys {
        val source = decodeCanonicalPasskey(remote)
            ?: return DecodedPasskeys(
                credentials = emptyList(),
                bindings = emptyList(),
            )

        source.sourceFields().forEach { field ->
            scope.consumeField(field.key)
        }
        scope.consumeField(PASSKEY_DISPLAY_FIELD_KEY)
        scope.consumeTag(PASSKEY_TAG)

        return DecodedPasskeys(
            credentials = listOf(source.toCredential(creationDate)),
            bindings = listOf(source.toBinding(remote)),
        )
    }

    fun encode(
        credentials: List<BitwardenCipher.Login.Fido2Credentials>,
        sourceData: CipherSourceData?,
    ): EncodedPasskeys {
        if (credentials.isEmpty()) {
            return EncodedPasskeys(
                writes = emptyList(),
                encodedCredentialIds = emptySet(),
                sourceData = sourceData.rebuildKeepass(
                    stripPaths = setOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS),
                    newBindings = emptyList(),
                ),
            )
        }

        val credential = credentials.first()
        val binding = credential.credentialId?.let { credentialId ->
            sourceData
                .keepassBindingsFor(setOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS))
                .bindingFor(
                    path = CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS,
                    selector = mapOf(SELECTOR_CREDENTIAL_ID to credentialId),
                )
        }
        // ROUN-2: the fixed KPEX field set only round-trips an ES256/P-256,
        // discoverable credential losslessly (decode hardcodes those values).
        // Anything else is left for the caller's lossless JSON blob path so its
        // metadata (counter, key algorithm/curve, discoverable, ...) is not lost.
        val encoded = credential
            .takeIf { it.isKpexRepresentable() }
            ?.let { encodeCredential(credential = it, binding = binding) }
        return if (encoded != null) {
            EncodedPasskeys(
                writes = encoded.writes,
                encodedCredentialIds = credential.credentialId
                    ?.let(::setOf)
                    ?: emptySet(),
                sourceData = sourceData.rebuildKeepass(
                    stripPaths = setOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS),
                    newBindings = listOf(encoded.binding),
                ),
            )
        } else {
            EncodedPasskeys(
                writes = emptyList(),
                encodedCredentialIds = emptySet(),
                sourceData = sourceData.rebuildKeepass(
                    stripPaths = setOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS),
                    newBindings = emptyList(),
                ),
            )
        }
    }

    private fun decodeCanonicalPasskey(
        remote: Entry,
    ): DecodedPasskeySource? {
        val username = selectField(
            remote = remote,
            canonicalKey = USERNAME_FIELD_KEY,
            fallbackKey = USERNAME_FALLBACK_FIELD_KEY,
            role = SOURCE_ROLE_USERNAME,
            representationId = KeePassPasskeyRepresentationIds.TEXT,
            validate = { it.isNotBlank() },
        ) ?: return null
        val credentialId = selectField(
            remote = remote,
            canonicalKey = CREDENTIAL_ID_FIELD_KEY,
            fallbackKey = GENERATED_USER_ID_FIELD_KEY,
            role = SOURCE_ROLE_CREDENTIAL_ID,
            representationId = KeePassPasskeyRepresentationIds.BASE64URL,
            validate = ::isValidBase64Url,
        ) ?: return null
        val privateKey = remote.fields[PRIVATE_KEY_FIELD_KEY]
            ?.takeIf { value -> isValidPrivateKeyPem(value.content) }
            ?.let { value ->
                DecodedPasskeySourceField(
                    key = PRIVATE_KEY_FIELD_KEY,
                    value = value,
                    role = SOURCE_ROLE_PRIVATE_KEY,
                    representationId = KeePassPasskeyRepresentationIds.PKCS8_PEM,
                )
            } ?: return null
        val relyingParty = remote.fields[RELYING_PARTY_FIELD_KEY]
            ?.takeIf { value -> value.content.isNotBlank() }
            ?.let { value ->
                DecodedPasskeySourceField(
                    key = RELYING_PARTY_FIELD_KEY,
                    value = value,
                    role = SOURCE_ROLE_RELYING_PARTY,
                    representationId = KeePassPasskeyRepresentationIds.TEXT,
                )
            } ?: return null
        val userHandle = remote.fields[USER_HANDLE_FIELD_KEY]
            ?.takeIf { value -> isValidBase64Url(value.content) }
            ?.let { value ->
                DecodedPasskeySourceField(
                    key = USER_HANDLE_FIELD_KEY,
                    value = value,
                    role = SOURCE_ROLE_USER_HANDLE,
                    representationId = KeePassPasskeyRepresentationIds.BASE64URL,
                )
            } ?: return null
        val backupEligible = remote.fields[BACKUP_ELIGIBLE_FIELD_KEY]
            ?.let { value -> parsePasskeyBool(value.content)?.let { value to it } }
        val backupState = remote.fields[BACKUP_STATE_FIELD_KEY]
            ?.let { value -> parsePasskeyBool(value.content)?.let { value to it } }

        return DecodedPasskeySource(
            username = username,
            credentialId = credentialId,
            privateKey = privateKey,
            relyingParty = relyingParty,
            userHandle = userHandle,
            backupEligible = backupEligible,
            backupState = backupState,
        )
    }

    private fun selectField(
        remote: Entry,
        canonicalKey: String,
        fallbackKey: String,
        role: SourceRole,
        representationId: SourceRepresentation,
        validate: (String) -> Boolean,
    ): DecodedPasskeySourceField? {
        val canonical = remote.fields[canonicalKey]
        if (canonical != null && validate(canonical.content)) {
            return DecodedPasskeySourceField(
                key = canonicalKey,
                value = canonical,
                role = role,
                representationId = representationId,
            )
        }
        val fallback = remote.fields[fallbackKey]
        if (fallback != null && validate(fallback.content)) {
            return DecodedPasskeySourceField(
                key = fallbackKey,
                value = fallback,
                role = role,
                representationId = representationId,
            )
        }
        return null
    }

    private fun DecodedPasskeySource.toCredential(
        creationDate: Instant,
    ): BitwardenCipher.Login.Fido2Credentials = BitwardenCipher.Login.Fido2Credentials(
        credentialId = credentialId.value.content,
        keyType = PUBLIC_KEY_CREDENTIAL_TYPE,
        keyAlgorithm = KEY_ALGORITHM_ECDSA,
        keyCurve = KEY_CURVE_P256,
        keyValue = privateKeyPemToBase64Der(privateKey.value.content)!!,
        rpId = relyingParty.value.content,
        rpName = null,
        counter = DEFAULT_COUNTER,
        userHandle = userHandle.value.content,
        userName = username.value.content,
        userDisplayName = username.value.content,
        discoverable = DEFAULT_DISCOVERABLE,
        creationDate = creationDate,
    )

    private fun DecodedPasskeySource.toBinding(
        remote: Entry,
    ): SourceBinding = sourceBinding(
        canonicalPaths = listOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS),
        sourceFields = sourceFields().map { field ->
            decodedSourceFieldRef(
                remote = remote,
                key = field.key,
                value = field.value,
                role = field.role,
                representationId = field.representationId,
            )
        },
        projectionId = KeePassPasskeyProjectionIds.KPEX_PASSKEY,
        selector = mapOf(SELECTOR_CREDENTIAL_ID to credentialId.value.content),
        parameters = buildMap {
            backupEligible?.let { put(PARAM_BACKUP_ELIGIBLE, it.second.toString()) }
            backupState?.let { put(PARAM_BACKUP_STATE, it.second.toString()) }
        },
    )

    // A credential round-trips losslessly through the KPEX field set only if it
    // matches the values decode hardcodes (toCredential): a public-key ES256/
    // P-256 key that is discoverable. Otherwise it must take the lossless blob
    // path so counter / key algorithm/curve / discoverable are not dropped.
    private fun BitwardenCipher.Login.Fido2Credentials.isKpexRepresentable(): Boolean =
        keyType == PUBLIC_KEY_CREDENTIAL_TYPE &&
                keyAlgorithm == KEY_ALGORITHM_ECDSA &&
                keyCurve == KEY_CURVE_P256 &&
                discoverable == DEFAULT_DISCOVERABLE

    private fun encodeCredential(
        credential: BitwardenCipher.Login.Fido2Credentials,
        binding: SourceBinding?,
    ): EncodedBindingPasskey? {
        val credentialId = credential.credentialId?.takeIf(::isValidBase64Url)
            ?: return null
        if (!isValidBase64Url(credential.userHandle)) return null
        if (credential.rpId.isBlank()) return null

        val privateKeyPem = privateKeyBase64DerToPem(credential.keyValue)
            ?: return null
        val username = credential.userName
            ?.takeIf { it.isNotBlank() }
            ?: credential.userDisplayName
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val backupEligible = binding
            ?.parameters
            ?.get(PARAM_BACKUP_ELIGIBLE)
            ?.toBooleanStrictOrNull()
            ?: DEFAULT_BACKUP_ELIGIBLE
        val backupState = binding
            ?.parameters
            ?.get(PARAM_BACKUP_STATE)
            ?.toBooleanStrictOrNull()
            ?: DEFAULT_BACKUP_STATE

        val writes = listOf(
            keepassFieldWrite(USERNAME_FIELD_KEY, username, concealed = false),
            keepassFieldWrite(CREDENTIAL_ID_FIELD_KEY, credentialId, concealed = true),
            keepassFieldWrite(PRIVATE_KEY_FIELD_KEY, privateKeyPem, concealed = true),
            keepassFieldWrite(RELYING_PARTY_FIELD_KEY, credential.rpId, concealed = false),
            keepassFieldWrite(USER_HANDLE_FIELD_KEY, credential.userHandle, concealed = true),
            keepassFieldWrite(BACKUP_ELIGIBLE_FIELD_KEY, backupEligible.toKeePassBool(), concealed = false),
            keepassFieldWrite(BACKUP_STATE_FIELD_KEY, backupState.toKeePassBool(), concealed = false),
        )
        val binding2 = sourceBinding(
            canonicalPaths = listOf(CipherSourceCanonicalPaths.LOGIN_FIDO2_CREDENTIALS),
            sourceFields = writes.mapIndexed { index, write ->
                encodedSourceFieldRef(
                    key = write.key,
                    concealed = write.value.isConcealed(),
                    role = roleForKey(write.key),
                    representationId = representationForKey(write.key),
                    order = index,
                )
            },
            projectionId = KeePassPasskeyProjectionIds.KPEX_PASSKEY,
            selector = mapOf(SELECTOR_CREDENTIAL_ID to credentialId),
            parameters = mapOf(
                PARAM_BACKUP_ELIGIBLE to backupEligible.toString(),
                PARAM_BACKUP_STATE to backupState.toString(),
            ),
        )
        return EncodedBindingPasskey(
            writes = writes,
            binding = binding2,
        )
    }

    private fun privateKeyPemToBase64Der(
        value: String,
    ): String? {
        val body = pemBody(value) ?: return null
        val der = base64Service.decodeOrNull(body) ?: return null
        return base64Service.encodeToString(der)
    }

    private fun privateKeyBase64DerToPem(
        value: String,
    ): String? {
        val der = base64Service.decodeOrNull(value) ?: return null
        val body = base64Service.encodeToString(der)
            .chunked(PEM_LINE_LENGTH)
            .joinToString("\n")
        return "$PRIVATE_KEY_BEGIN\n$body\n$PRIVATE_KEY_END"
    }

    private fun isValidPrivateKeyPem(
        value: String,
    ): Boolean = privateKeyPemToBase64Der(value) != null

    private fun pemBody(
        value: String,
    ): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith(PRIVATE_KEY_BEGIN)) return null
        if (!trimmed.endsWith(PRIVATE_KEY_END)) return null
        return trimmed
            .removePrefix(PRIVATE_KEY_BEGIN)
            .removeSuffix(PRIVATE_KEY_END)
            .filterNot { it.isWhitespace() }
            .takeIf { it.isNotEmpty() }
    }

    private fun isValidBase64Url(
        value: String,
    ): Boolean {
        if (!BASE64_URL_REGEX.matches(value)) return false
        return runCatching {
            PasskeyBase64.decode(value).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun parsePasskeyBool(
        value: String,
    ): Boolean? = when (value.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private fun Boolean.toKeePassBool(): String = if (this) "1" else "0"

    private fun DecodedPasskeySource.sourceFields(): List<DecodedPasskeySourceField> = buildList {
        add(username)
        add(credentialId)
        add(privateKey)
        add(relyingParty)
        add(userHandle)
        backupEligible?.let { (value, _) ->
            add(
                DecodedPasskeySourceField(
                    key = BACKUP_ELIGIBLE_FIELD_KEY,
                    value = value,
                    role = SOURCE_ROLE_BACKUP_ELIGIBLE,
                    representationId = KeePassPasskeyRepresentationIds.BOOL,
                ),
            )
        }
        backupState?.let { (value, _) ->
            add(
                DecodedPasskeySourceField(
                    key = BACKUP_STATE_FIELD_KEY,
                    value = value,
                    role = SOURCE_ROLE_BACKUP_STATE,
                    representationId = KeePassPasskeyRepresentationIds.BOOL,
                ),
            )
        }
    }

    private fun roleForKey(key: String): SourceRole? = when (key) {
        USERNAME_FIELD_KEY -> SOURCE_ROLE_USERNAME
        CREDENTIAL_ID_FIELD_KEY -> SOURCE_ROLE_CREDENTIAL_ID
        PRIVATE_KEY_FIELD_KEY -> SOURCE_ROLE_PRIVATE_KEY
        RELYING_PARTY_FIELD_KEY -> SOURCE_ROLE_RELYING_PARTY
        USER_HANDLE_FIELD_KEY -> SOURCE_ROLE_USER_HANDLE
        BACKUP_ELIGIBLE_FIELD_KEY -> SOURCE_ROLE_BACKUP_ELIGIBLE
        BACKUP_STATE_FIELD_KEY -> SOURCE_ROLE_BACKUP_STATE
        else -> null
    }

    private fun representationForKey(key: String): SourceRepresentation? = when (key) {
        USERNAME_FIELD_KEY, RELYING_PARTY_FIELD_KEY -> KeePassPasskeyRepresentationIds.TEXT
        CREDENTIAL_ID_FIELD_KEY, USER_HANDLE_FIELD_KEY -> KeePassPasskeyRepresentationIds.BASE64URL
        PRIVATE_KEY_FIELD_KEY -> KeePassPasskeyRepresentationIds.PKCS8_PEM
        BACKUP_ELIGIBLE_FIELD_KEY, BACKUP_STATE_FIELD_KEY -> KeePassPasskeyRepresentationIds.BOOL
        else -> null
    }

    data class DecodedPasskeys(
        val credentials: List<BitwardenCipher.Login.Fido2Credentials>,
        val bindings: List<SourceBinding>,
    )

    data class EncodedPasskeys(
        val writes: List<KeePassFieldWrite>,
        val encodedCredentialIds: Set<String>,
        val sourceData: CipherSourceData?,
    )

    private data class DecodedPasskeySource(
        val username: DecodedPasskeySourceField,
        val credentialId: DecodedPasskeySourceField,
        val privateKey: DecodedPasskeySourceField,
        val relyingParty: DecodedPasskeySourceField,
        val userHandle: DecodedPasskeySourceField,
        val backupEligible: Pair<EntryValue, Boolean>?,
        val backupState: Pair<EntryValue, Boolean>?,
    )

    private data class DecodedPasskeySourceField(
        val key: String,
        val value: EntryValue,
        val role: SourceRole,
        val representationId: SourceRepresentation,
    )

    private data class EncodedBindingPasskey(
        val writes: List<KeePassFieldWrite>,
        val binding: SourceBinding,
    )

    private companion object {
        const val USERNAME_FIELD_KEY = "KPEX_PASSKEY_USERNAME"
        const val USERNAME_FALLBACK_FIELD_KEY = "KPXC_PASSKEY_USERNAME"
        const val CREDENTIAL_ID_FIELD_KEY = "KPEX_PASSKEY_CREDENTIAL_ID"
        const val GENERATED_USER_ID_FIELD_KEY = "KPEX_PASSKEY_GENERATED_USER_ID"
        const val PRIVATE_KEY_FIELD_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
        const val RELYING_PARTY_FIELD_KEY = "KPEX_PASSKEY_RELYING_PARTY"
        const val USER_HANDLE_FIELD_KEY = "KPEX_PASSKEY_USER_HANDLE"
        const val BACKUP_ELIGIBLE_FIELD_KEY = "KPEX_PASSKEY_FLAG_BE"
        const val BACKUP_STATE_FIELD_KEY = "KPEX_PASSKEY_FLAG_BS"
        const val PASSKEY_DISPLAY_FIELD_KEY = "Passkey"
        const val PASSKEY_TAG = "Passkey"

        val SOURCE_ROLE_USERNAME = SourceRole("username")
        val SOURCE_ROLE_CREDENTIAL_ID = SourceRole("credentialId")
        val SOURCE_ROLE_PRIVATE_KEY = SourceRole("privateKey")
        val SOURCE_ROLE_RELYING_PARTY = SourceRole("relyingParty")
        val SOURCE_ROLE_USER_HANDLE = SourceRole("userHandle")
        val SOURCE_ROLE_BACKUP_ELIGIBLE = SourceRole("backupEligible")
        val SOURCE_ROLE_BACKUP_STATE = SourceRole("backupState")

        const val SELECTOR_CREDENTIAL_ID = "credentialId"
        const val PARAM_BACKUP_ELIGIBLE = "backupEligible"
        const val PARAM_BACKUP_STATE = "backupState"

        const val PUBLIC_KEY_CREDENTIAL_TYPE = "public-key"
        const val KEY_ALGORITHM_ECDSA = "ECDSA"
        const val KEY_CURVE_P256 = "P-256"
        const val DEFAULT_COUNTER = "0"
        const val DEFAULT_DISCOVERABLE = "true"
        const val DEFAULT_BACKUP_ELIGIBLE = true
        const val DEFAULT_BACKUP_STATE = true

        const val PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----"
        const val PRIVATE_KEY_END = "-----END PRIVATE KEY-----"
        const val PEM_LINE_LENGTH = 64

        val BASE64_URL_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }
}

internal object KeePassPasskeyProjectionIds {
    val KPEX_PASSKEY = SourceProjectionId("keepass.passkey.kpex")
}

internal object KeePassPasskeyRepresentationIds {
    val TEXT = SourceRepresentation("keepass.passkey.text")
    val BASE64URL = SourceRepresentation("keepass.passkey.base64url")
    val PKCS8_PEM = SourceRepresentation("keepass.passkey.pkcs8-pem")
    val BOOL = SourceRepresentation("keepass.passkey.bool")
}
