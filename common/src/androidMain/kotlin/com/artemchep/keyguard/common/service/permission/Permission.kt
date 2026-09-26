package com.artemchep.keyguard.common.service.permission

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

@SuppressLint("InlinedApi")
actual enum class Permission(
    val permission: String,
    val minSdk: Int = 0,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, minSdk = 33),
    WRITE_EXTERNAL_STORAGE(Manifest.permission.WRITE_EXTERNAL_STORAGE, maxSdk = 29),
    LOCAL_NETWORK(
        permission = Manifest.permission.ACCESS_LOCAL_NETWORK,
        minSdk = Build.VERSION_CODES.CINNAMON_BUN,
    ),
}
