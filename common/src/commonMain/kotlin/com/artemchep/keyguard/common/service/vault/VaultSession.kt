package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import kotlinx.coroutines.flow.StateFlow

/** Identity and lifetime of one unlocked vault; contains no dependency-container API. */
interface VaultSession {
    val id: String
    val active: StateFlow<Boolean>

    /** Prevents further resolution before the owner publishes a replacement. */
    fun retire()

    /** Retires the session, cancels its work, and releases it. Repeated calls have no effect. */
    fun close()
}

interface VaultSessionFactory {
    /** Restores a persisted session without eagerly opening its database. */
    fun create(masterKey: MasterKey): VaultSession

    /** Opens the database before the authenticated session can be published. */
    suspend fun createAuthenticated(masterKey: MasterKey): VaultSession
}

fun interface VaultDatabaseSessionAccess {
    fun get(session: VaultSession): VaultDatabaseManager?
}
