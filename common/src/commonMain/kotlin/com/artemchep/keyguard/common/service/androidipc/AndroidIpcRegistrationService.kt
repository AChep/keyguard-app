package com.artemchep.keyguard.common.service.androidipc

import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class AndroidIpcRegisteredApp(
    val packageName: String,
    val appLabel: String,
    val certificateDigests: List<String>,
    val registeredAtEpochMilliseconds: Long,
    val lastUsedAtEpochMilliseconds: Long,
    val installed: Boolean,
    val signerMismatch: Boolean,
)

interface AndroidIpcRegistrationService {
    fun registrations(): Flow<List<AndroidIpcRegisteredApp>>

    fun revoke(packageName: String): IO<Unit>
}

object AndroidIpcRegistrationServiceNone : AndroidIpcRegistrationService {
    override fun registrations(): Flow<List<AndroidIpcRegisteredApp>> =
        flowOf(emptyList())

    override fun revoke(packageName: String): IO<Unit> = {
        Unit
    }
}
