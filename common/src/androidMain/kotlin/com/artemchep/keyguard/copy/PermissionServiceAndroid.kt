package com.artemchep.keyguard.copy

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
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

class PermissionServiceAndroid(
    private val context: Context,
) : PermissionService {
    companion object {
        const val REQUEST_CODE = 12341
    }

    private val refreshSink = EventFlow<Unit>()

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
                checkPermission(permission)
            }
    }

    private fun checkPermission(
        permission: Permission,
    ): PermissionState {
        val isGranted = context.checkSelfPermission(permission.permission) ==
                PackageManager.PERMISSION_GRANTED
        return if (isGranted) {
            PermissionState.Granted
        } else {
            PermissionState.Declined(
                permission = permission,
                ask = ::askPermission
                    .partially1(permission.permission),
                openSettings = ::openAppSettings,
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

    private fun openAppSettings(
        context: LeContext,
    ) {
        val androidContext = context.context
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${androidContext.packageName}".toUri(),
        )
        if (androidContext !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        kotlin.runCatching {
            androidContext.startActivity(intent)
        }
    }

    // Android

    fun refresh() {
        refreshSink.emit(Unit)
    }
}
