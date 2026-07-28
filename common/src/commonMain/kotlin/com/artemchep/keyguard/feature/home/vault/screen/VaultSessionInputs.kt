package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Owns the repository inputs shared by one unlocked vault session.
 *
 * Every supplied use case is invoked exactly once while this owner is constructed.
 * Consumers must still apply the hidden-profile privacy gate to [ciphers] and
 * [profiles] before publishing rows. In particular, [ciphers] is a raw session
 * source, not a renderable cipher list.
 *
 * [concealFields] deliberately has no initial value. A caller therefore cannot
 * accidentally render an unconcealed fallback while the persisted preference is
 * still loading.
 *
 * The owner has an independent child job. [close] cancels every upstream
 * collection without cancelling the caller's scope. The caller must then discard
 * this owner, including its replay caches; a closed instance must never be reused
 * for another session.
 */
internal class VaultSessionInputs(
    scope: CoroutineScope,
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getAccounts: GetAccounts,
    getCanWrite: GetCanWrite,
    getConcealFields: GetConcealFields,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
) : AutoCloseable {
    private val sessionJob = SupervisorJob(
        parent = scope.coroutineContext[Job],
    )
    private val sessionScope = CoroutineScope(
        scope.coroutineContext + sessionJob,
    )

    val ciphers: SharedFlow<List<DSecret>> = getCiphers()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val profiles: SharedFlow<List<DProfile>> = getProfiles()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val organizations: SharedFlow<List<DOrganization>> = getOrganizations()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val collections: SharedFlow<List<DCollection>> = getCollections()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = STOP_TIMEOUT_MILLIS,
            ),
            replay = 1,
        )

    val accounts: SharedFlow<List<DAccount>> = getAccounts()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val canWrite: StateFlow<WriteCapability> = getCanWrite()
        .map { allowed ->
            if (allowed) {
                WriteCapability.Allowed
            } else {
                WriteCapability.Denied
            }
        }
        .stateIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            initialValue = WriteCapability.Unknown,
        )

    val concealFields: SharedFlow<Boolean> = getConcealFields()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val appIcons: SharedFlow<Boolean> = getAppIcons()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    val websiteIcons: SharedFlow<Boolean> = getWebsiteIcons()
        .shareIn(
            scope = sessionScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    override fun close() {
        sessionScope.cancel()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
