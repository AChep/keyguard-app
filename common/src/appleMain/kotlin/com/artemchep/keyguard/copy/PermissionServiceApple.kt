package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.notification.impl.AppleNotificationAuthorization
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Notification authorization is managed here. Storage access uses user-selected files,
 * and local-network access is prompted by the OS when a connection is attempted.
 */
class PermissionServiceApple(
    private val windowCoroutineScope: WindowCoroutineScope,
) : PermissionService {
    private val refreshSink = EventFlow<Unit>()

    private val notificationsDeclined = PermissionState.Declined(
        permission = Permission.POST_NOTIFICATIONS,
        openSettings = {
            windowCoroutineScope.launch(Dispatchers.Main) {
                openPermissionSettings()
            }
        },
        ask = {
            windowCoroutineScope.launch {
                AppleNotificationAuthorization.request()
                refreshSink.emit(Unit)
            }
        },
    )

    override fun getState(
        permission: Permission,
    ): Flow<PermissionState> = when (permission) {
        Permission.POST_NOTIFICATIONS -> refreshSink
            .onStart { emit(Unit) }
            .map { notificationState() }

        Permission.WRITE_EXTERNAL_STORAGE,
        Permission.LOCAL_NETWORK,
        -> flowOf(PermissionState.Granted)
    }

    private suspend fun notificationState(): PermissionState {
        val status = AppleNotificationAuthorization.status()
        return if (AppleNotificationAuthorization.isGranted(status)) {
            PermissionState.Granted
        } else {
            notificationsDeclined
        }
    }
}

internal expect fun openPermissionSettings()
