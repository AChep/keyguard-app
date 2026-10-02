package com.artemchep.keyguard.apple.settings

/** Draft values for one setup presentation. Credentials and folder grants stay in the native editor. */
data class BackupSetupSnapshot(
    val loaded: Boolean = false,
    val initializationFailed: Boolean = false,
    val enabled: Boolean = false,
    val isTestingLocation: Boolean = false,
    val error: String? = null,
    val storeKind: String = "local",
    val localPath: String? = null,
    val webDavUrl: String? = null,
    val webDavUsername: String? = null,
    val hasWebDavPassword: Boolean = false,
    val hasPassword: Boolean = false,
    val includeAttachments: Boolean = true,
    val retentionMaxSnapshots: Int = 30,
) {
    companion object {
        val empty = BackupSetupSnapshot()
    }
}
