package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DAccountStatus
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetLocalNetworkAccessHint
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetSends
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GetAccountStatusImpl(
    private val permissionService: PermissionService,
    private val getAccounts: GetAccounts,
    private val getMetas: GetMetas,
    private val getCiphers: GetCiphers,
    private val getFolders: GetFolders,
    private val getSends: GetSends,
    private val getLocalNetworkAccessHint: GetLocalNetworkAccessHint,
) : GetAccountStatus {
    override fun invoke(): Flow<DAccountStatus> {
        val lastSyncTimestampFlow = getMetas()
            .map { metas ->
                metas
                    .mapNotNull { meta -> meta.lastSyncTimestamp }
                    .maxOrNull()
            }
            .distinctUntilChanged()
        val hasFailureFlow = kotlin.run {
            val m = getMetas()
                .map { metas ->
                    // If any of the account has failed to sync, then we report
                    // it as an error.
                    metas.count { it.lastSyncResult is DMeta.LastSyncResult.Failure }
                }
                .distinctUntilChanged()
            val c = getCiphers()
                .map {
                    it.count { it.hasError }
                }
            val f = getFolders()
                .map {
                    it.count { it.hasError }
                }
            combine(c, f, m) { a, b, c -> a + b + c }
        }
        val hasPendingFlow = kotlin.run {
            val c = getCiphers()
                .map {
                    it.count { !it.synced }
                }
            val f = getFolders()
                .map {
                    it.count { !it.synced }
                }
            val s = getSends()
                .map {
                    it.count { !it.synced }
                }
            combine(c, f, s) { a, b, s -> a + b + s }
        }

        val pendingPermissionsFlow = combine(
            permissionService
                .getState(Permission.POST_NOTIFICATIONS)
                .map { notificationPermissionState ->
                    notificationPermissionState as? PermissionState.Declined
                },
            permissionService
                .getState(Permission.LOCAL_NETWORK)
                .flatMapLatest { localNetworkPermissionState ->
                    if (localNetworkPermissionState is PermissionState.Declined) {
                        return@flatMapLatest getLocalNetworkAccessHint()
                            .map { showHint ->
                                localNetworkPermissionState
                                    .takeIf { showHint }
                            }
                    }

                    flowOf(null)
                },
        ) { notificationsPermission, localNetworkPermissions ->
            buildList {
                localNetworkPermissions?.let(::add)
                notificationsPermission?.let(::add)
            }
        }
        return combine(
            lastSyncTimestampFlow,
            hasFailureFlow,
            hasPendingFlow,
            pendingPermissionsFlow,
        ) { lastSyncTimestamp, errorCount, pendingCount, pendingPermissions ->
            DAccountStatus(
                lastSyncTimestamp = lastSyncTimestamp,
                error = DAccountStatus.Error(errorCount)
                    .takeIf { errorCount > 0 },
                pending = DAccountStatus.Pending(pendingCount)
                    .takeIf { pendingCount > 0 },
                pendingPermissions = pendingPermissions,
            )
        }
    }
}
