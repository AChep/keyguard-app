package com.artemchep.keyguard.ipctestclient.ipc

import org.openintents.openpgp.util.OpenPgpApi

/** How the provider treats the caller's output pipe for a given action. */
enum class OpenPgpOutputMode {
    /** The pipe must exist; the request is rejected without one. */
    REQUIRED,

    /** The pipe is used when supplied, and the payload is dropped otherwise. */
    OPTIONAL,

    /** The payload is always dropped, even when a pipe is supplied. */
    DISCARD,

    /** The action produces no stream at all. */
    NONE,
}

/** A request extra the driver can edit, and the suite can vary. */
enum class OpenPgpField {
    PAYLOAD,
    USER_IDS,
    SINGLE_USER_ID,
    KEY_IDS,
    SELECTED_KEY_IDS,
    SIGN_KEY_ID,
    PRESELECT_KEY_ID,
    KEY_ID,
    ORIGINAL_FILENAME,
    ASCII_ARMOR,
    COMPRESSION,
    OPPORTUNISTIC,
    DETACHED_SIGNATURE,
}

/**
 * The OpenPGP API surface, as Keyguard implements it.
 *
 * [supported] marks the three actions the API defines but Keyguard rejects; they
 * are listed so both the driver and the suite can pin the rejection instead of
 * pretending the protocol stops at what is implemented.
 */
// ACTION_SIGN is deprecated in the API library in favour of ACTION_CLEARTEXT_SIGN,
// but Keyguard still accepts it and clients in the wild still send it, so it stays
// covered here.
@Suppress("LongParameterList", "DEPRECATION")
enum class OpenPgpOperation(
    val action: String,
    val label: String,
    val needsInput: Boolean,
    val outputMode: OpenPgpOutputMode,
    val fields: Set<OpenPgpField>,
    val usesPrivateKey: Boolean = false,
    val supported: Boolean = true,
) {
    CHECK_PERMISSION(
        action = OpenPgpApi.ACTION_CHECK_PERMISSION,
        label = "Check permission",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = emptySet(),
    ),
    GET_SIGN_KEY_ID(
        action = OpenPgpApi.ACTION_GET_SIGN_KEY_ID,
        label = "Get sign key id",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = setOf(OpenPgpField.SIGN_KEY_ID, OpenPgpField.PRESELECT_KEY_ID),
    ),
    GET_SIGN_KEY_ID_LEGACY(
        action = OpenPgpApi.ACTION_GET_SIGN_KEY_ID_LEGACY,
        label = "Get sign key id (legacy)",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = setOf(OpenPgpField.SIGN_KEY_ID, OpenPgpField.PRESELECT_KEY_ID),
    ),
    GET_KEY_IDS(
        action = OpenPgpApi.ACTION_GET_KEY_IDS,
        label = "Get key ids",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = RECIPIENT_FIELDS,
    ),
    GET_KEY(
        action = OpenPgpApi.ACTION_GET_KEY,
        label = "Get key",
        needsInput = false,
        outputMode = OpenPgpOutputMode.REQUIRED,
        fields = setOf(OpenPgpField.KEY_ID, OpenPgpField.ASCII_ARMOR),
    ),
    QUERY_AUTOCRYPT_STATUS(
        action = OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS,
        label = "Query autocrypt status",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = RECIPIENT_FIELDS,
    ),
    SIGN(
        action = OpenPgpApi.ACTION_SIGN,
        label = "Sign",
        needsInput = true,
        outputMode = OpenPgpOutputMode.REQUIRED,
        fields = setOf(OpenPgpField.PAYLOAD, OpenPgpField.SIGN_KEY_ID),
        usesPrivateKey = true,
    ),
    CLEARTEXT_SIGN(
        action = OpenPgpApi.ACTION_CLEARTEXT_SIGN,
        label = "Cleartext sign",
        needsInput = true,
        outputMode = OpenPgpOutputMode.REQUIRED,
        fields = setOf(OpenPgpField.PAYLOAD, OpenPgpField.SIGN_KEY_ID),
        usesPrivateKey = true,
    ),
    DETACHED_SIGN(
        action = OpenPgpApi.ACTION_DETACHED_SIGN,
        label = "Detached sign",
        needsInput = true,
        outputMode = OpenPgpOutputMode.NONE,
        fields = setOf(
            OpenPgpField.PAYLOAD,
            OpenPgpField.SIGN_KEY_ID,
            OpenPgpField.ASCII_ARMOR,
        ),
        usesPrivateKey = true,
    ),
    ENCRYPT(
        action = OpenPgpApi.ACTION_ENCRYPT,
        label = "Encrypt",
        needsInput = true,
        outputMode = OpenPgpOutputMode.REQUIRED,
        fields = ENCRYPT_FIELDS,
    ),
    SIGN_AND_ENCRYPT(
        action = OpenPgpApi.ACTION_SIGN_AND_ENCRYPT,
        label = "Sign and encrypt",
        needsInput = true,
        outputMode = OpenPgpOutputMode.REQUIRED,
        fields = ENCRYPT_FIELDS + OpenPgpField.SIGN_KEY_ID,
        usesPrivateKey = true,
    ),
    DECRYPT_VERIFY(
        action = OpenPgpApi.ACTION_DECRYPT_VERIFY,
        label = "Decrypt and verify",
        needsInput = true,
        outputMode = OpenPgpOutputMode.OPTIONAL,
        fields = setOf(OpenPgpField.PAYLOAD, OpenPgpField.DETACHED_SIGNATURE),
    ),
    DECRYPT_METADATA(
        action = OpenPgpApi.ACTION_DECRYPT_METADATA,
        label = "Decrypt metadata",
        needsInput = true,
        outputMode = OpenPgpOutputMode.DISCARD,
        fields = setOf(OpenPgpField.PAYLOAD, OpenPgpField.DETACHED_SIGNATURE),
    ),
    BACKUP(
        action = OpenPgpApi.ACTION_BACKUP,
        label = "Backup (unsupported)",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = emptySet(),
        supported = false,
    ),
    UPDATE_AUTOCRYPT_PEER(
        action = OpenPgpApi.ACTION_UPDATE_AUTOCRYPT_PEER,
        label = "Update autocrypt peer (unsupported)",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = emptySet(),
        supported = false,
    ),
    AUTOCRYPT_KEY_TRANSFER(
        action = OpenPgpApi.ACTION_AUTOCRYPT_KEY_TRANSFER,
        label = "Autocrypt key transfer (unsupported)",
        needsInput = false,
        outputMode = OpenPgpOutputMode.NONE,
        fields = emptySet(),
        supported = false,
    ),
    ;

    companion object {
        val SUPPORTED: List<OpenPgpOperation> = entries.filter { it.supported }
        val UNSUPPORTED: List<OpenPgpOperation> = entries.filterNot { it.supported }

        /** Lowest and highest API versions the provider accepts. */
        const val MIN_API_VERSION = 7
        const val MAX_API_VERSION = 12
        const val DEFAULT_API_VERSION = MAX_API_VERSION
    }
}

private val RECIPIENT_FIELDS = setOf(
    OpenPgpField.USER_IDS,
    OpenPgpField.SINGLE_USER_ID,
    OpenPgpField.KEY_IDS,
)

private val ENCRYPT_FIELDS = RECIPIENT_FIELDS + setOf(
    OpenPgpField.PAYLOAD,
    OpenPgpField.SELECTED_KEY_IDS,
    OpenPgpField.ORIGINAL_FILENAME,
    OpenPgpField.ASCII_ARMOR,
    OpenPgpField.COMPRESSION,
    OpenPgpField.OPPORTUNISTIC,
)
