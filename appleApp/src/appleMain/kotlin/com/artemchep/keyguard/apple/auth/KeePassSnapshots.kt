package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.add.AddFilePickerKind
import com.artemchep.keyguard.apple.model.TextFieldSnapshot

/** A mode tab ("open" / "new") of the KeePass add-account form. */
data class KeePassTabSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** A database-location choice ("local" / "webdav" / "s3") of the KeePass add-account form. */
data class KeePassLocationSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** The chosen database / key file; [size] is -1 when unknown (e.g. WebDAV or S3). */
data class KeePassFileSnapshot(
    val name: String?,
    val size: Long,
)

/**
 * Selecting a mode tab launches the shared producer's file picker (open / create database), surfaced through
 * [KeePassLoginSession.setKeePassFilePickerRequestHandler], so there is no separate "browse" affordance for the first
 * selection.
 */
data class KeePassLoginSnapshot(
    val tabs: List<KeePassTabSnapshot>,
    val locations: List<KeePassLocationSnapshot>,
    val dbFile: KeePassFileSnapshot?,
    val canClearDbFile: Boolean,
    val keyFile: KeePassFileSnapshot?,
    val canClearKeyFile: Boolean,
    val password: TextFieldSnapshot,
    val canSubmit: Boolean,
    val isLoading: Boolean,
) {
    companion object {
        val empty = KeePassLoginSnapshot(
            tabs = emptyList(),
            locations = emptyList(),
            dbFile = null,
            canClearDbFile = false,
            keyFile = null,
            canClearKeyFile = false,
            password = TextFieldSnapshot.empty("keepass.password"),
            canSubmit = false,
            isLoading = false,
        )
    }
}

/**
 * Unlike [com.artemchep.keyguard.apple.add.AddFilePickerRequest] the resolution MUST keep a persistent reference
 * to the picked file: resolve with the ORIGINAL url plus a security-scoped bookmark access token via
 * [KeePassLoginSession.resolveKeePassFilePicker], never with a temp copy, since the database is synced and written back
 * for the lifetime of the account.
 */
data class KeePassFilePickerRequest(
    val requestId: String,
    val kind: AddFilePickerKind,
    val mimeTypes: List<String>,
    val suggestedName: String?,
)

/**
 * [errorKind] is a [com.artemchep.keyguard.feature.webdav.WebDavSettingsState.Error] name ("UrlRequired" /
 * "InvalidUrl" / "FileUrlRequired" / "PasswordRequiresUsername"), or null.
 */
data class WebDavSettingsSnapshot(
    val id: String,
    val url: String,
    val username: String,
    val password: String,
    val errorKind: String?,
    val isTestingConnection: Boolean,
)

/**
 * [key] is the object key of the database. [errorKind] is a [com.artemchep.keyguard.feature.s3.S3FormError] name
 * ("EndpointInvalid" / "BucketRequired" / "BucketInvalid" / "PrefixInvalid" / "KeyRequired" / "KeyInvalid" /
 * "KeyExtensionRequired" / "AccessKeyIdRequired" / "SecretAccessKeyRequired"), or null.
 */
data class S3SettingsSnapshot(
    val id: String,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val key: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyle: Boolean,
    val errorKind: String?,
    val isTestingConnection: Boolean,
    val fieldErrors: List<S3SettingsFieldErrorSnapshot>,
    val validationRequest: Int,
    val validationField: String?,
)

data class S3SettingsFieldErrorSnapshot(
    val id: String,
    val kind: String,
)
