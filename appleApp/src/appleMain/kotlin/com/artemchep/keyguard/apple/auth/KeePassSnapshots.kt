package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.add.AddFilePickerKind
import com.artemchep.keyguard.apple.model.TextFieldSnapshot

/** A mode tab ("open" / "new") of the KeePass add-account form. */
data class KeePassTabSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** A database-location choice ("local" / "webdav") of the KeePass add-account form. */
data class KeePassLocationSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** The chosen database / key file; [size] is -1 when unknown (e.g. WebDAV). */
data class KeePassFileSnapshot(
    val name: String?,
    val size: Long,
)

/**
 * Selecting a mode tab launches the shared producer's file picker (open / create database), surfaced through
 * [KeyguardCore.setKeePassFilePickerRequestHandler], so there is no separate "browse" affordance for the first
 * selection.
 */
data class KeePassLoginSnapshot(
    val tabs: List<KeePassTabSnapshot>,
    val locations: List<KeePassLocationSnapshot>,
    val isWebDav: Boolean,
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
            isWebDav = false,
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
 * [KeyguardCore.resolveKeePassFilePicker], never with a temp copy, since the database is synced and written back
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
    val url: String,
    val username: String,
    val password: String,
    val errorKind: String?,
    val isTestingConnection: Boolean,
)
