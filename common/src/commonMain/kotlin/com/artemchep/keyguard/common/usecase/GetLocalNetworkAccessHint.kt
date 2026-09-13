package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.coroutines.flow.Flow

/**
 * Reports whether any persisted account or automatic-backup configuration can
 * communicate with a service on the local network.
 *
 * This only controls permission hints. Custom endpoints may be public, so this
 * classification must never block a connection when LAN permission is denied.
 */
interface GetLocalNetworkAccessHint : () -> Flow<Boolean>

fun ServiceToken.mayAccessLocalNetwork(): Boolean = when (this) {
    is BitwardenToken -> env.hasExplicitEndpoint()
    is KeePassToken -> database.location.mayAccessLocalNetwork()
}

fun FileLocation.mayAccessLocalNetwork(): Boolean = when (this) {
    is FileLocation.WebDav,
    is FileLocation.Sftp,
    -> true

    is FileLocation.Dropbox,
    is FileLocation.GoogleDrive,
    is FileLocation.Local,
    is FileLocation.OneDrive,
    -> false
}

/**
 * Reports whether syncing the account needs a network connection.
 */
fun ServiceToken.syncRequiresNetwork(): Boolean = when (this) {
    is BitwardenToken -> true
    is KeePassToken -> database.location.syncRequiresNetwork()
}

// Document providers may expose local or cached files. Let the provider
// decide whether the file is accessible instead of blocking all URIs.
fun FileLocation.syncRequiresNetwork(): Boolean = this !is FileLocation.Local

fun BackupConfig.mayAccessLocalNetwork(): Boolean =
    enabled && store is BackupStoreConfig.WebDav && store.isConfigured

private fun BitwardenToken.Environment.hasExplicitEndpoint(): Boolean = sequenceOf(
    baseUrl,
    webVaultUrl,
    apiUrl,
    identityUrl,
    iconsUrl,
).any(String::isNotBlank)
