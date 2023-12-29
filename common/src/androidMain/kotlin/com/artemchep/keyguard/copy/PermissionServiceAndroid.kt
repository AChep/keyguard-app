package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import arrow.core.partially1
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PermissionServiceAndroid(
    private val context: Context,
) : PermissionService {
    companion object {
        const val REQUEST_CODE = 12341
    }

    private val refreshSink = EventFlow<Unit>()

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun getState(
        permission: Permission,
    ): Flow<PermissionState> {
        val sdk = Build.VERSION.SDK_INT
        if (
            sdk < permission.minSdk ||
            sdk > permission.maxSdk
        ) {
            val result = PermissionState.Granted
            return MutableStateFlow(result)
        }

        return refreshSink
            .onStart { emit(Unit) }
            .map {
                // Check the status of the permission.
                checkPermission(permission.permission)
            }
    }

    private fun checkPermission(permission: String): PermissionState {
        val isGranted = context.checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
        return if (isGranted) {
            PermissionState.Granted
        } else {
            PermissionState.Declined(
                ask = ::askPermission
                    .partially1(permission),
            )
        }
    }

    private fun askPermission(
        permission: String,
        context: LeContext,
    ) {
        val activity = context.context.closestActivityOrNull
            ?: return
        activity.requestPermissions(
            arrayOf(permission),
            REQUEST_CODE,
        )
    }

    // Android

    fun refresh() {
        refreshSink.emit(Unit)
    }
}
