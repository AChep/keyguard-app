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
    val s3Endpoint: String? = null,
    val s3Region: String? = null,
    val s3Bucket: String? = null,
    val s3Prefix: String? = null,
    val s3AccessKeyId: String? = null,
    val s3PathStyle: Boolean = true,
    /** The S3 destination as `s3://bucket/prefix`. */
    val s3Location: String? = null,
    /** The host of a custom S3 endpoint, or null for Amazon S3. */
    val s3EndpointHost: String? = null,
    val hasPassword: Boolean = false,
    val includeAttachments: Boolean = true,
    val retentionMaxSnapshots: Int = 30,
) {
    companion object {
        val empty = BackupSetupSnapshot()
    }
}
