package com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta

/**
 * Extracts metadata from local and server entities for diff computation.
 *
 * Each entity type (cipher, folder, send, etc.) implements this to
 * provide type-specific metadata extraction. The extracted [LocalItemMeta]
 * and [ServerItemMeta] are compared by [SyncDiffer][com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncDiffer]
 * to produce [SyncAction][com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction]s.
 *
 * @param Local the local entity type (e.g. [BitwardenCipher][com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher]).
 * @param Server the server entity type (e.g. [CipherEntity][com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity]).
 */
interface EntitySyncStrategy<Local : BitwardenService.Has<Local>, Server : Any> {
    fun toLocalItemMeta(entity: Local): LocalItemMeta
    fun toServerItemMeta(entity: Server): ServerItemMeta
}
