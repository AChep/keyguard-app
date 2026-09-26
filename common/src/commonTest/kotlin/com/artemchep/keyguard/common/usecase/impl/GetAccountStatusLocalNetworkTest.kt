package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DAccountStatus
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetLocalNetworkAccessHint
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetSends
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetAccountStatusLocalNetworkTest {
    @Test
    fun `permission hints follow grant revocation and account changes without hiding sync failures`() = runTest {
        val localPermission = MutableStateFlow<PermissionState>(declined(Permission.LOCAL_NETWORK))
        val showHint = MutableStateFlow(true)
        val failure = DMeta.LastSyncResult.Failure(timestamp = Instant.fromEpochSeconds(1))
        val getStatus = GetAccountStatusImpl(
            permissionService = object : PermissionService {
                override fun getState(permission: Permission): Flow<PermissionState> = when (permission) {
                    Permission.LOCAL_NETWORK -> localPermission
                    else -> flowOf(declined(permission))
                }
            },
            getAccounts = object : GetAccounts {
                override fun invoke(): Flow<List<DAccount>> = flowOf(emptyList())
            },
            getCiphers = object : GetCiphers {
                override fun invoke(): Flow<List<DSecret>> = flowOf(emptyList())
            },
            getFolders = object : GetFolders {
                override fun invoke(): Flow<List<DFolder>> = flowOf(emptyList())
            },
            getSends = object : GetSends {
                override fun invoke(): Flow<List<DSend>> = flowOf(emptyList())
            },
            getMetas = object : GetMetas {
                override fun invoke(): Flow<List<DMeta>> = flowOf(
                    listOf(DMeta(accountId = AccountId("account"), lastSyncResult = failure)),
                )
            },
            getLocalNetworkAccessHint = object : GetLocalNetworkAccessHint {
                override fun invoke(): Flow<Boolean> = showHint
            },
        )
        var status: DAccountStatus? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            getStatus().collect { status = it }
        }
        runCurrent()

        fun assertPermissions(vararg permissions: Permission) {
            val current = assertNotNull(status)
            assertEquals(permissions.toList(), current.pendingPermissions.map { it.permission })
            assertEquals(1, current.error?.count)
        }

        assertPermissions(Permission.LOCAL_NETWORK, Permission.POST_NOTIFICATIONS)
        localPermission.value = PermissionState.Granted
        runCurrent()
        assertPermissions(Permission.POST_NOTIFICATIONS)

        localPermission.value = declined(Permission.LOCAL_NETWORK)
        runCurrent()
        assertPermissions(Permission.LOCAL_NETWORK, Permission.POST_NOTIFICATIONS)

        showHint.value = false
        runCurrent()
        assertPermissions(Permission.POST_NOTIFICATIONS)
    }

    private fun declined(permission: Permission) = PermissionState.Declined(
        permission = permission,
        ask = {},
        openSettings = {},
    )
}
