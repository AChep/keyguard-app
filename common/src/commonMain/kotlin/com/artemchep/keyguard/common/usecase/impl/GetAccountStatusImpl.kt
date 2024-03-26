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
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.util.flow.combineToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAccountStatusImpl(
    private val permissionService: PermissionService,
    private val getAccounts: GetAccounts,
    private val getMetas: GetMetas,
    private val getCiphers: GetCiphers,
    private val getFolders: GetFolders,
    private val getSends: GetSends,
) : GetAccountStatus {
    private val importantPermissions = listOf(
        Permission.POST_NOTIFICATIONS,
    )

    constructor(directDI: DirectDI) : this(
        permissionService = directDI.instance(),
        getAccounts = directDI.instance(),
        getMetas = directDI.instance(),
        getCiphers = directDI.instance(),
        getFolders = directDI.instance(),
        getSends = directDI.instance(),
    )

    override fun invoke(): Flow<DAccountStatus> {
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

        val pendingPermissionsFlow = importantPermissions
            .map { permission ->
                permissionService
                    .getState(permission)
            }
            .combineToList()
            .map { permissionStates ->
                permissionStates
                    .filterIsInstance<PermissionState.Declined>()
            }
        return combine(
            hasFailureFlow,
            hasPendingFlow,
            pendingPermissionsFlow,
        ) { errorCount, pendingCount, pendingPermissions ->
            DAccountStatus(
                error = DAccountStatus.Error(errorCount)
                    .takeIf { errorCount > 0 },
                pending = DAccountStatus.Pending(pendingCount)
                    .takeIf { pendingCount > 0 },
                pendingPermissions = pendingPermissions,
            )
        }
    }
}
