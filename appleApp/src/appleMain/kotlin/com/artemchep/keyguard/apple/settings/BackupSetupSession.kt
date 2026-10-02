package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.common.service.file.FileAccessToken

/** One wizard's draft, verification, and completion. Methods and callbacks are main-confined. */
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

    fun setRetention(maxSnapshots: Int) = session.withActions { it?.edit { setRetention(maxSnapshots) } }

    fun submit() = session.withActions { it?.submit() }

    fun close() = session.close()
}
