package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.common.service.file.FileAccessToken

/** One wizard's draft, verification, and completion. Methods and callbacks are main-confined. */
@Suppress("TooManyFunctions")
class BackupSetupSession internal constructor(
    private val subscribe: (
        publish: (BackupSetupSnapshot, BackupSetupEditor?) -> Unit,
        complete: () -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<BackupSetupSnapshot, BackupSetupEditor?>()

    fun observe(onChange: (BackupSetupSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose, subscribe)

    fun setIncludeAttachments(value: Boolean) = session.withActions { it?.edit { setIncludeAttachments(value) } }

    fun setPassword(text: String) = session.withActions { it?.edit { setPassword(text) } }

    fun restorePassword() = session.withActions { it?.restorePassword() }

    fun setStoreKind(kind: String) = session.withActions {
        val storeKind = when (kind) {
            "local" -> BackupStoreKind.Local
            "webdav" -> BackupStoreKind.WebDav
            "s3" -> BackupStoreKind.S3
            else -> return@withActions
        }
        it?.edit { setStoreKind(storeKind) }
    }

    /** The owning Swift picker supplies the folder's persistent security-scoped grant. */
    fun setLocalDirectory(path: String, accessToken: String) = session.withActions {
        it?.edit {
            if (config.store is BackupStoreConfig.Local) {
                setStore(BackupStoreConfig.Local(path = path, accessToken = FileAccessToken(accessToken)))
            }
        }
    }

    fun setWebDav(url: String, username: String, password: String) = session.withActions {
        it?.edit { setWebDav(url, username, password) }
    }

    /** An empty [secretAccessKey] keeps the saved key while the endpoint, bucket and access key are unchanged. */
    fun setS3(
        endpoint: String,
        region: String,
        bucket: String,
        prefix: String,
        accessKeyId: String,
        secretAccessKey: String,
        pathStyle: Boolean,
    ) = session.withActions {
        it?.edit { setS3(endpoint, region, bucket, prefix, accessKeyId, secretAccessKey, pathStyle) }
    }

    /** Whether [setS3] with an empty secret access key keeps the key of this account. */
    fun keepsS3SecretAccessKey(endpoint: String, bucket: String, accessKeyId: String): Boolean {
        var keeps = false
        session.withActions { keeps = it?.keepsS3SecretAccessKey(endpoint, bucket, accessKeyId) == true }
        return keeps
    }

    /** The `S3FormError` name of the first invalid [setS3] value, or null when all are valid. */
    fun s3ErrorKind(
        endpoint: String,
        region: String,
        bucket: String,
        prefix: String,
        accessKeyId: String,
        secretAccessKey: String,
        pathStyle: Boolean,
    ): String? {
        var kind: String? = null
        session.withActions {
            kind = it?.s3Error(endpoint, region, bucket, prefix, accessKeyId, secretAccessKey, pathStyle)?.name
        }
        return kind
    }

    fun setRetention(maxSnapshots: Int) = session.withActions { it?.edit { setRetention(maxSnapshots) } }

    fun submit() = session.withActions { it?.submit() }

    fun close() = session.close()
}
